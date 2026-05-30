# Battery Control Hand-back & Degraded Mode — Design

**Date:** 2026-05-30
**Status:** Approved (design), pending implementation plan
**Scope:** Server-side battery control logic (`EnergyManager`, `SurplusPriorityStrategy`,
`SMABattery`), the `/ws` control protocol, app entry (`Application.kt`), and a standalone
hardware test tool. No Android changes (a follow-up is noted).

## Problem

The EMS commands the SMA battery over Modbus by writing **802** (enable Modbus power
control) to register 40151, then a signed target power to 40149. The current code does this
on **every** 5 s AUTO tick and **never** writes **803** (return control to the inverter). Two
consequences:

1. Once AUTO runs, the inverter's own logic is locked out **indefinitely** — there is no
   path that ever hands control back.
2. The control register (802) is re-armed every tick even when nothing changed (redundant
   writes), and the write results are ignored (`// TODO: what to do with result?`).

We want the EMS to hold Modbus control only while it is genuinely steering the battery, and
to release to the inverter otherwise. We also want the battery to keep doing useful work when
only *some* device readings are available, instead of skipping the tick entirely.

## Principles

- **Hold control only while steering.** Release to the inverter (803) whenever the EMS is not
  actively commanding the battery.
- **Fail toward the inverter.** When the EMS goes blind, hand back rather than hold a stale
  target.
- **Degrade, don't freeze.** The battery's core job is to balance the grid to ~0, which needs
  only grid + battery readings. Missing charger/heat-pump data should drop us to a simpler
  control law, not stop battery control.
- **Don't write needlessly.** Only write to the battery when the command meaningfully changes.

## 1. Battery command model

Introduce an explicit command type (new file `ems/BatteryCommand.kt` or alongside `Strategy`):

```kotlin
sealed interface BatteryCommand {
    data class SetPower(val power: Watt) : BatteryCommand   // 802 + target 40149
    data object ReleaseToInverter : BatteryCommand          // 803
}
```

The `Battery` interface (`devices/battery/Battery.kt`) gains a release path:

```kotlin
interface Battery {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<BatteryState>?
    suspend fun setChargingPower(power: Watt)   // 802 + target
    suspend fun releaseToInverter()             // 803
}
```

**Write-on-change guard lives in `SMABattery`** (it owns its register state). Track:

- `engaged: Boolean` — whether 802 control is currently enabled (starts `false`).
- `lastTarget: Watt?` — last target written while engaged.

Behavior:

- `setChargingPower(p)`:
  - if `!engaged`: write 802, then write target `p`; set `engaged = true`, `lastTarget = p`.
  - if `engaged` and `abs(p - lastTarget) > POWER_EPSILON_W` (25 W): write target `p` only;
    `lastTarget = p`.
  - else: no Modbus write (no-op).
- `releaseToInverter()`:
  - if `engaged`: write 803; set `engaged = false`, `lastTarget = null`.
  - else: no-op.
- **Check Modbus write results** (fixes the existing TODO): if the digitalpetri response
  indicates failure, throw so the caller's `try/catch` in `EnergyManager` logs it. (The guard
  state is only updated after a successful write, so a failed write is retried next tick.)

`POWER_EPSILON_W = 25` is a named constant in `SMABattery`.

## 2. Three-tier control logic

Each AUTO tick selects a tier from the readings present in `EMSState`:

| Tier | Condition | Action |
|------|-----------|--------|
| **1 — Full** | grid, battery, charger, heat pump all present | Existing surplus cascade (heat pump → charger → battery). Emits charger + heat-pump commands and `SetPower(remainder)`. |
| **2 — Degraded** | grid + battery present; charger and/or heat pump missing | Do **not** command charger/heat pump (leave them as-is). Steer battery only: `SetPower(currentBatteryPower − gridPower)`. |
| **3 — Blind** | grid OR battery missing | Increment blind counter. After **6 consecutive** blind ticks (~30 s), emit `ReleaseToInverter` once. Before that, hold (emit nothing). |

Any tier-1 or tier-2 tick **resets the blind counter to 0**.

### Degraded control law (tier 2)

Grid sign convention: negative = exporting, positive = importing. To drive grid → 0, the
battery must absorb the current grid flow on top of what it's already doing:

```
batteryTarget = currentBatteryPower − gridPower
```

- Grid importing (`gridPower > 0`) → target decreases (discharge more / charge less).
- Grid exporting (`gridPower < 0`) → target increases (charge more).

This is a one-step proportional correction applied each tick; convergence over a few ticks is
acceptable (no integral term — YAGNI).

### Placement

- **Tier selection + blind counter + mode handling** live in `EnergyManager` (control
  concerns: "what data do I have," "what mode am I in").
- **Decision math** stays in `SurplusPriorityStrategy`, exposed as two functions on the
  `Strategy` interface:
  - `decide(snapshot: WorldSnapshot): ControlDecisions` — existing full cascade. Its
    `batteryTargetPower: Watt?` is reinterpreted as producing `BatteryCommand.SetPower`.
  - `decideDegraded(gridPower: Watt, batteryPower: Watt): Watt` — returns the tier-2 battery
    target. Pure and unit-testable.

`ControlDecisions.batteryTargetPower: Watt?` is replaced by
`batteryCommand: BatteryCommand?` (null = no change this tick). `EnergyManager.applyDecisions`
dispatches `SetPower` → `battery.setChargingPower`, `ReleaseToInverter` →
`battery.releaseToInverter`.

`buildWorldSnapshot` is no longer the gate for *all* control. Tier selection inspects
`EMSState` directly: grid+battery present is the minimum for control; charger absence drops to
tier 2 rather than returning null. (The full `WorldSnapshot` is still built for tier 1.)

## 3. MANUAL / AUTO mode

`Mode { AUTO, MANUAL }` already exists on `EnergyManager` but is unreachable (no setter) and
inert (MANUAL just skips the tick). Redefine and wire it:

- **MANUAL = "release all; every device runs its own logic."** On the **AUTO → MANUAL
  transition** (tracked via a `previousMode` field), once:
  - Battery → `releaseToInverter()` (clean).
  - Heat pump → `setConsumeMode(ConsumeMode.Unrestricted)` (SG-Ready normal; clean).
  - Charger → **best-effort**: stop sending setpoints. A full revert requires stopping the
    Webasto keepalive (register 6000), which is **not** implemented here — **TODO** noted in
    code.
  - While in MANUAL, the loop sends no device commands.
- **MANUAL → AUTO:** the next normal tick resumes steering (re-arms battery via `SetPower`).

### API exposure

Add to the existing authenticated `/ws` protocol (`Messages.kt` / `Sockets.kt`):

- `ClientMessage.SetMode(mode: ManagerMode)` where `ManagerMode { AUTO, MANUAL }` is a new
  `@Serializable` enum (the wire type; maps to `EnergyManager.Mode`).
- `Sockets.kt` handles `SetMode` (only when authenticated) by calling a new
  `energyManager.setMode(...)` — a proper setter that records the change so the run loop picks
  it up. The `/ws` handler must receive the `EnergyManager` reference (currently it only gets
  `emsStateFlow`); thread it through `Application.module`.
- Current mode is reported back to clients. Add it to the outbound state: extend `EMSState`
  (and the `Message.PowerUsageUpdate` payload, or a small dedicated `Message` variant) with
  the active mode so the app can reflect it.

**Android follow-up (out of scope):** the app needs a MANUAL/AUTO toggle and to display the
current mode. Captured here as a TODO; no Android work in this spec.

## 4. Graceful shutdown

Register a JVM shutdown hook in `Application.kt`:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking {
        withTimeoutOrNull(3_000) {
            world.batteries.values.forEach { runCatching { it.releaseToInverter() } }
        }
    }
})
```

- Writes 803 on normal stop/restart — the **primary** hand-back mechanism.
- Bounded by a 3 s timeout so a hung Modbus write cannot wedge shutdown.
- **Hard kill / power loss is explicitly out of scope:** no software can write 803 when the
  process is gone. The SMA's own watchdog is too slow to rely on (observed ≥ 15 min), which is
  exactly why the graceful 803 is required rather than optional.

## 5. Standalone watchdog test tool

New `tools/BatteryWatchdogTest.kt` with its own `main()`, run via a Gradle `JavaExec` task
(e.g. `./gradlew batteryWatchdogTest`):

- Loads the battery host from `config.yaml` (reuse `loadConfig`).
- Arms control: 802 + a modest charge target (e.g. 500 W).
- Polls and prints SoC + net power every ~3 s, **forever**.
- The operator Ctrl-Cs it **without** sending 803, then observes whether/when the inverter
  reverts on its own.
- Header comment documents the procedure and a **TODO: must be run against real hardware** to
  confirm the watchdog timeout (already observed ≥ 15 min; this verifies/quantifies it).

This tool is operational, not a unit test; it never runs in CI.

## Testing

- **`SurplusPriorityStrategy.decideDegraded`** — pure unit tests: importing grid → discharge;
  exporting grid → charge; zero grid → hold at current battery power; sign/magnitude checks.
- **`SMABattery` guard** — unit-test the engaged/lastTarget state machine with a faked Modbus
  client: first `setChargingPower` writes 802+target; a within-epsilon repeat writes nothing;
  a beyond-epsilon change writes target only; `releaseToInverter` writes 803 only when
  engaged; failed write throws and does not advance guard state.
- **`EnergyManager` tier selection & blind counter** — with fakes: full readings → tier 1
  command; missing charger → tier 2 battery command, no charger/heat-pump writes; missing grid
  → no command until 6 ticks, then one `ReleaseToInverter`; a good tick resets the counter;
  AUTO→MANUAL transition releases battery + sets heat pump normal once.
- Existing tests must continue to pass; `ControlDecisions` field rename will require updates.

## Out of scope

- Android MANUAL/AUTO UI (follow-up TODO).
- Webasto keepalive stop / rigorous charger release (TODO; charger release is best-effort).
- Hard-kill / power-loss battery safety (physically unsolvable in software).
- Any integral/PID term in the degraded control law.
