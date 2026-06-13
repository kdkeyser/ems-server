# Architecture Review — EMS Server (2026-06-11)

Scope: the Kotlin/Ktor backend (`src/main/kotlin`), ~4,000 lines. This review looks at
concept definitions, module boundaries, and opportunities to simplify. It deliberately does
not re-litigate the control *logic* (deadbeat battery loop, surplus cascade) — that is well
reasoned and well documented in place.

## Overall assessment

The codebase is in good shape for its size and purpose. The core pipeline —
devices → `World` → `DataCollector` (read) / `EnergyManager` (control) → `Strategy`
(pure decision) → apply — is the right shape: the strategy seam (`WorldSnapshot` in,
`ControlDecisions` out, no side effects) is the single best architectural decision here, and
the tiered degradation in `EnergyManager.tick()` (full / degraded / blind) shows the failure
modes were thought through. Hardware quirks are documented at the exact line where they bite.

The weaknesses are not structural rot but **concept duplication**: the same idea exists in
two or three slightly different shapes in different modules (snapshots of "all device
power", the EMS mode, the charger intent, watts-vs-amps), and a few boundaries where intent
is tunneled through a channel that wasn't designed to carry it. Each finding below states
the problem, why it matters, and a concrete way to address it.

---

## A. Core concepts and data flow

### A1. Three overlapping "snapshot of all devices" types

The same concept — *the current power readings of every device* — exists three times:

| Type | Built by | Fields | Purpose |
|------|----------|--------|---------|
| `EMSState` (`ems/EMSState.kt`) | `EnergyManager.buildEMSState()` | nullable `Int` | app/WS clients, history |
| `WorldSnapshot` (`ems/Strategy.kt`) | `EnergyManager.buildWorldSnapshot()` | typed `Watt` | strategy input |
| `StatusState` (`StatusState.kt`) | `DataCollector.refresh()` | nullable `Int` + health | status page |

Two specific problems fall out of this:

1. **`WorldSnapshot` is built *from* `EMSState`**, so device readings round-trip
   typed (`Watt`) → untyped (`Int?`) → typed (`Watt`) again, and the nulls are patched with
   fake zeros on the way (`solarPower ?: 0`, `batteryCharge ?: 0`, `heatpumpPower ?: 0` in
   `EnergyManager.kt:241-251`). A strategy cannot distinguish "heat pump reads 0 W" from
   "heat pump reading missing" — today that's masked because tier-1 requires
   `heatpumpPower != null`, but the type permits the bug.

2. **`DataCollector` rebuilds the same aggregate a second way**, by scraping its own
   `healthMap` — including parsing the battery SoC back out of a *display string*
   (`extraInfo.removeSuffix("% SoC")?.toIntOrNull()`, `DataCollector.kt:81`). Data is encoded
   into human-readable text and then machine-parsed three lines later. `DeviceHealth.Online`
   conflates two concepts: telemetry for the status page (display) and a data channel
   (powerW, SoC).

**Suggestion.** Define one canonical readings type — essentially today's `EMSState` but with
the staleness rule applied at construction — produced in exactly one place, and derive the
other two views from it:

- `StatusState` = canonical readings + per-device health. `DataCollector` already has typed
  `state.update` in hand inside each poll closure; keep the values typed (e.g. add
  `batterySoc: Int?` to `DeviceHealth.Online` or carry a small per-device reading struct)
  instead of round-tripping through `extraInfo`.
- `WorldSnapshot` = canonical readings + config-derived charger limits + override. Build it
  from typed values, not from `EMSState`'s `Int?` fields, and keep its fields non-null only
  where the strategy actually requires them (see A4).

This removes the string-parse hack, the double aggregation, and the fake-zero coercions in
one move. Effort: medium.

### A2. Two free-running 5-second loops sample the world incoherently

`DataCollector.refresh()` (Application.kt:82-87) and `EnergyManager.run()`
(EnergyManager.kt:99-104) are independent 5 s loops with no phase relationship. The strategy
doc comment in `SurplusPriorityStrategy.kt:24-28` already names the consequence: grid and
battery are sampled at unrelated moments, so the "exact" deadbeat correction overshoots on
fast ramps. The control loop acts on data whose age varies anywhere from 0 to ~5 s, and the
`STALE_AFTER`/`fresh()` machinery exists partly to paper over that.

**Suggestion.** Make the control tick *follow* the poll instead of racing it:

```kotlin
while (true) {
    dataCollector.refresh()   // bounded by the 10s per-device timeout
    energyManager.tick()      // acts on the freshest coherent set
    delay(5_000)
}
```

This guarantees every tick sees readings at most one poll old, halves the worst-case
data age, and is *less* code (one loop instead of two). The staleness guard stays — it
still protects against a device whose poll keeps failing while `getState()` retains the
last value. Note the semantic shift: when many devices time out, ticks stretch toward 10 s,
so `BLIND_RELEASE_TICKS` should be defined in seconds rather than ticks. The strategy's
own caveat (SMA smoothing) remains, but the polled-loop skew disappears. Effort: small;
biggest payoff per line changed in this review.

### A3. The device interface forces a caching protocol on every driver

Every device interface is `update()` + `getState(): DeviceUpdate<T>?`, which obliges each
driver to carry the same boilerplate: a `Mutex`, an `internalState` var, a
`DeviceUpdate(markNow(), …)` wrap, and a trace log — duplicated in `P1Meter`, `SMASolar`,
`SMABattery`, `Webasto`, `DaikinHeatpump`. Meanwhile `OcppCharger.update()` is a no-op
because push-based devices don't fit the poll-then-read shape at all; it fabricates a fresh
`DeviceUpdate` on every `getState()` call instead.

The two-phase protocol exists so `DataCollector` (writes) and `EnergyManager` (reads) can
share state, but the cache itself is not device behaviour — it's collector behaviour that
leaked into five drivers.

**Suggestion.** Collapse the interface to a single `suspend fun read(): GridState` (etc.)
that returns the current value or throws, and move the cache — one
`Map<String, DeviceUpdate<*>>` with the timestamp and the staleness rule — into the
collector (or a small `DeviceCache`). Drivers shrink to pure protocol code; `OcppCharger`'s
no-op disappears naturally (its `read()` just consults `OcppService`); the staleness policy
(`fresh()`, currently in `EnergyManager`) lands next to the timestamps it judges; and the
mutex count in the codebase drops by five. Control methods (`setChargingPower`,
`setMaxChargerPower`, `setConsumeMode`) stay on the device interfaces. Effort: medium;
mechanical but touches every driver.

### A4. Tier classification is inferred from field nullability, not from configuration

`tick()` decides between tier 1 (full cascade) and tier 2 (battery-only) by testing
`emsState.chargerPower != null && emsState.heatpumpPower != null`
(EnergyManager.kt:144). Consequences:

- A system configured with battery + solar + heat pump but **no charger** can never reach
  tier 1 — the heat pump is permanently unmanaged — because `chargerPower` is always null
  and `buildWorldSnapshot()` additionally bails without a charger config
  (EnergyManager.kt:239). "Tier 1 needs full data" silently became "tier 1 needs a charger".
- The reverse confusion is also possible: a *configured* charger that is merely offline is
  indistinguishable from an *unconfigured* one.

**Suggestion.** Separate the two questions: *what is configured* (static, from `Config`) and
*what is currently readable* (dynamic, from staleness). The snapshot should carry
`chargerPower: Watt?` where null means "configured but unreadable", and omit unconfigured
devices from the tier decision entirely; the strategy already handles a forced-off charger,
so letting it handle an absent one (treat as 0 W contribution) is a small step. This also
lets you delete `WorldSnapshot.solarPower` and `batteryCharge` — **neither strategy reads
them today** — or alternatively start using `batteryCharge` for a SoC floor, which is
currently delegated entirely to the inverter's own protection. Effort: small–medium.

### A5. "There is exactly one charger/battery/heat pump" is half-enforced

`World` holds `Map<String, Charger>` etc., but `EnergyManager` reads only
`firstOrNull()` for state (`buildEMSState`), while commands fan out to *all* devices
(`applyDecisions`, `releaseAll`). `Config.startupWarnings()` exists solely to warn about
this asymmetry, and `chargerKey` (`chargePointId ?: name`) re-derives device identity a
second way for persistence.

**Suggestion.** Make the model match reality: `World` holds `solar: Map<String, Solar>`
(genuinely plural) and `charger: Charger?`, `battery: Battery?`, `heatPump: SmartConsumer?`.
Reject multi-entry config at load instead of warning. This deletes the warnings, the
`firstOrNull()` scattering, the read-one-command-all asymmetry, and makes `chargerKey`
trivial. If multi-battery is a plausible future, keep the list — but then the *reads* must
aggregate, not take the first; today's halfway point is the worst of both. Effort: small.

---

## B. The control path

### B1. Charger intent is tunneled through a wattage setter

The charger on/off intent travels as a magic value through a power channel:
`EnergyManager` computes override amps → multiplies by 230 into a `Watt` →
`OcppCharger.setMaxChargerPower` divides by 230 back into amps → **interprets `amps == 0`
as "stop the OCPP transaction" and `amps > 0` as "ensure one is open"**
(OcppCharger.kt:63-83). The contract "0 A reliably means session-off" only holds because of
a separate invariant in `SurplusPriorityStrategy` (active solar sessions never drop below
`chargerMinAmps`) — a coupling between a strategy implementation detail and a device
driver's protocol interpretation, documented only in comments.

There are also **three representations of the same intent**: `ChargerControl`
(wire message in `Messages.kt`, doubling as `EnergyManager`'s internal state),
`ChargerControlRecord` (persistence, `ocpp/db/OcppStores.kt`), and the encoded
amps-via-watts value at the device boundary.

**Suggestion.** Give the charger an explicit command, exactly like the battery already has
(`BatteryCommand` is the right pattern — follow it):

```kotlin
sealed interface ChargerCommand {
    data class Charge(val current: Ampere) : ChargerCommand  // implies session open
    data object Stop : ChargerCommand                        // implies session closed
}
suspend fun Charger.apply(cmd: ChargerCommand)
```

`ControlDecisions.chargerMaxAmps: Int?` becomes `chargerCommand: ChargerCommand?`, the
0-means-stop convention disappears, the amps↔watts round trip disappears, and the
strategy/driver coupling becomes a type instead of a comment. Effort: small–medium, high
clarity payoff.

### B2. 230 V single-phase is hardcoded while `GridType` config goes unused

`config.grid.gridType` (Phase1 / Phase3_230V / Phase3_400V) is parsed, logged at startup,
stored in `GridProperties`… and never read again. Meanwhile the amps↔watts conversion
hardcodes single-phase 230 V in five places: `SurplusPriorityStrategy.kt:53`,
`EnergyManager.kt:229,257`, `OcppCharger.kt:65`, `Webasto.kt:85`. On a 3-phase 400 V
installation every conversion is wrong by ~3×.

**Suggestion.** Either commit to single-phase and delete `GridType`/`GridProperties`
(honest and smallest), or define one conversion in one place —
`fun GridType.ampsToWatts(a: Int): Watt` — and thread it through. Adopting `ChargerCommand`
(B1) shrinks this to a single conversion site inside the strategy. Don't leave config that
promises behaviour the code ignores. Effort: small.

### B3. The EMS mode exists twice, as two enums and two state holders

`ems.Mode` (AUTO/MANUAL) and `ManagerMode` (AUTO/MANUAL) are the same concept, mapped back
and forth in `EnergyManager.setMode()` and `Sockets.kt:111-113`. Within `EnergyManager`,
the value is *also* stored twice: a `@Volatile var mode` and a `modeFlow` StateFlow, updated
in lockstep.

**Suggestion.** Keep one serializable enum (`ManagerMode`, since the wire name is pinned)
and one holder (`MutableStateFlow`); `tick()` reads `modeFlow.value` — a StateFlow read is
as cheap and as thread-safe as the volatile. Deletes the mapping code and a class. Effort:
trivial.

### B4. `EnergyManager` accretes non-control responsibilities

At 301 lines it is still readable, but it currently owns: the control loop, mode state,
staleness policy (A3 moves this out), the charger-intent store + persistence
(`loadChargerControl`/`setCharging`), the history tap, and a pure pass-through
`carSocFlow` that exists only because `Sockets` receives nothing but an `EnergyManager`
(EnergyManager.kt:62, and note `EMSState.carCharge` duplicates the same value a second
way). The trend is that every new client-visible value gets bolted onto `EnergyManager`.

**Suggestion.** Two extractions, both small:

1. **Charger intent** → a `ChargerControlService` (holds the `StateFlow`, loads/persists via
   `ChargerControlStore`, knows `effectiveChargerAmps`). `EnergyManager` consumes its value
   in `tick()`; `Sockets` and `OcppWebUi` talk to it directly instead of through
   `EnergyManager.setCharging`.
2. **Client-facing flows** → pass `Sockets` what it actually needs (a small façade or the
   individual services) instead of routing `carSocFlow` through the control component.
   Then drop either `carSocFlow` or `EMSState.carCharge` — one channel for car SoC, not two.

Effort: small each; do them when next touching the respective code rather than as a
dedicated refactor.

---

## C. Module boundaries

### C1. `devices` depends on `ocpp` — the dependency points the wrong way

`OcppCharger` (devices layer) takes a concrete `OcppService` (protocol-server layer), and
`World.fromConfig(config, ocppService)` threads it through. The devices package is otherwise
transport-agnostic (drivers own their Modbus/HTTP clients); OCPP is the exception, and the
`World` factory signature is the visible symptom — it will grow another parameter for every
future push-based transport.

**Suggestion.** Define the narrow port `OcppCharger` actually uses, *in the charger package*:

```kotlin
interface OcppChargePointPort {
    fun latestPowerW(connectorId: Int): Int?
    fun connectorStatus(connectorId: Int): ChargePointStatus?
    fun activeTransactionId(connectorId: Int): Int?
    fun isPowerControlCapable(): Boolean
    suspend fun setChargingProfile(connectorId: Int, amps: Double): Boolean
    suspend fun remoteStart(idTag: String, connectorId: Int): Boolean
    suspend fun remoteStop(txId: Int): Boolean
}
```

`OcppService` provides an adapter (already partially curried by `chargePointId`). The
dependency arrow flips (ocpp implements a devices-defined port), `OcppCharger` tests lose
the service mock, and `World.fromConfig` takes a port factory rather than the whole service.
Effort: small. (Pragmatic note: with one OCPP charger in one house this is the most
"architectural" suggestion here — do it for the testing benefit or skip it consciously.)

### C2. `OcppService` is becoming a god object — but the seams are already visible

403 lines covering: session registry, eight inbound protocol handlers, persistence
orchestration, request/response correlation (`sendCall`/`completeCall`/`failCall`),
typed outbound commands, capability probing, allow-list/idTag admin, and the web-UI view
models (`OcppState`/`OcppChargePointView` live in this file too).

It is still coherent, and I would *not* split it preemptively. If it grows further, the
natural cut lines are: (1) the call-correlation mechanism (`pending` map + `sendCall` +
outbound command helpers) into an `OcppCallClient`; (2) the view-model
`recomputeState()`/`OcppState` into the web-UI module that consumes it. Flagged so the next
feature lands on the right side of those lines. Effort: defer.

### C3. EMS concepts live in the OCPP database package

`ChargerControlStore` sits in `ocpp/db/OcppStores.kt` (table `OcppChargerControl`), but
charger intent is an *EMS* concept — it applies equally to the Webasto, which has no OCPP at
all. Similarly `cardata`'s token store shares `openDatabase` from `ocpp.db`. The SQLite
database has quietly become the app-wide store while its module name still says "ocpp".

**Suggestion.** Rename/move the generic persistence seam (`Db.kt`, `ChargerControlStore`)
to a neutral `persistence`/`db` package; OCPP-specific stores (charge points, idTags,
transactions) stay in `ocpp/db`. Pure file-move, no behaviour change. Effort: trivial,
do alongside B4's extraction.

### C4. Wire protocol types double as domain types

`Messages.kt` (root package) defines the Android wire protocol — and `ChargerControl`,
`ChargerMode`, `ManagerMode` from it are imported into `ems` as internal state. The comment
in the file correctly warns the serial names must stay in sync with the app; reusing those
classes as domain state means an internal refactor can silently become a protocol break.
At this project's scale, full DTO separation is overkill — but be aware the coupling is
load-bearing, and keep `Messages.kt` free of any types the app doesn't actually receive.

---

## D. Cleanups (low effort, do opportunistically)

1. **Dead types in `devices/Devices.kt`**: `Wh`, `Angle`, `Ampere`, `BatteryProperties`,
   `SolarProperties`, `HeatPumpProperties`, and the commented-out `ChargerProperties` are
   all unused. Delete them (or resurrect `Ampere` for B1 — it's the one worth keeping).
   `Grid.properties`/`GridProperties` is write-only (see B2).

2. **Package naming**: `io.konektis.devices.Solar` (capitalised) for `SMASolar` vs
   `devices.solar` for its interface; `devices.Heatpump` for `DaikinHeatpump` vs
   `devices.smartConsumers` for its interface vs directory `smartConsumer` (singular).
   Normalise to lowercase singular packages matching directories.

3. **Naming collisions**: the wire enum `Devices` (Messages.kt) vs config class `Devices`
   (Config.kt) vs the `devices` package; wire `Update` vs internal `DeviceUpdate`. Rename
   the wire types on the server side only if you also rename in the app — otherwise leave,
   but don't add a fourth `Devices`.

4. **Ktor template leftovers**: `configureRouting()` (Hello World on `/`),
   `configureHTTP()` (`X-Engine: Ktor` header) can be deleted outright;
   `configureMonitoring()`'s CallLogging format logs response headers at INFO for every
   request — replace with the default format or drop. `Routing.kt`/`Monitoring.kt` carry
   ~20 unused imports each (rate limiter, sessions, Exposed). `Security.kt`'s realm string
   `"Access to the '/' path"` is template filler.

5. **Three identically-configured CIO `HttpClient`s** in `AppModule` (generic, history
   writer, history repository). The history pair exists for timeout isolation from device
   polling — fine — but writer and repository can share one client and one `@Provides`.

6. **`Application.module()` signature** threads six component fields individually; pass the
   `AppComponent` (or introduce nothing and accept it — it's only one call site). Watch
   that it doesn't grow further.

7. **`deserializeMessage()`** in Messages.kt (decoding *server*-bound `Message`s) is only
   used by tests of the protocol itself; fine, but it signals the file serves two masters
   (see C4).

---

## Suggested order of attack

| Priority | Item | Why first | Effort |
|----------|------|-----------|--------|
| 1 | A2 — merge the two 5 s loops | Improves control coherence; *removes* code | S |
| 2 | B1 + B2 — `ChargerCommand`, one amps↔watts site | Removes the riskiest implicit contract (0 A = stop) | S–M |
| 3 | A1 — single canonical snapshot, fix the SoC string-parse | Biggest concept-duplication win | M |
| 4 | A5 — singular charger/battery/heat-pump in `World` | Deletes warnings + asymmetry; precondition for A1 being clean | S |
| 5 | B3 — one mode enum/holder | Trivial deletion | S |
| 6 | D4, D1, D2 — template leftovers, dead types, package naming | Hygiene | S |
| 7 | A3 — move caching out of drivers | Mechanical, large surface; nice-to-have | M |
| 8 | A4 — explicit tier model | Only matters if a charger-less or multi-device config becomes real | S–M |
| 9 | B4, C3 — extract charger intent + move stores | Do when next touching that code | S |
| 10 | C1 — OCPP port inversion | Testing benefit; optional at this scale | S |
| — | C2 — OcppService split | Defer until it grows again | — |

A final calibration note: this is a single-operator home system with one of each device.
Suggestions A5, B2-delete-GridType, and "reject multi-device config" all push toward
*shrinking* the model to match that reality rather than generalising. The alternative —
actually supporting multi-device and 3-phase — is legitimate but should be a deliberate
decision, not the accidental residue of `Map<String, _>` and an unused enum.
