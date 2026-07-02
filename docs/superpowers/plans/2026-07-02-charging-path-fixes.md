# Charging-Path Reliability & Simplification Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all fixes from the 2026-07-02 charging-path code review: solar-session hysteresis, OCPP reconnect race, capability restore, MeterValues staleness, command dedup/backoff, blind-tier charger stop, web-UI intent routing, typed SoC, dead-type deletion.

**Architecture:** All changes stay inside the existing seams: `OcppService` (protocol state), `OcppCharger` (device driver), `SurplusPriorityStrategy` (pure-ish decision logic — gains session-hysteresis state), `EnergyManager` (control loop), `OcppWebUi` (REST). No new modules.

**Tech Stack:** Kotlin, Ktor, kotlinx-serialization, Exposed/SQLite, mockk + kotlin.test (JUnit5), `kotlin.time.TestTimeSource` for time-dependent tests.

## Global Constraints

- Work on branch `fix/charging-path-review` (create from `main` before Task 1).
- Commit style: conventional commits with scope, e.g. `fix(ocpp): ...`, matching `git log`.
- Test command: `./gradlew test --tests "<FQCN>"` per task; `./gradlew build` at the end.
- Power sign convention: negative = producing/exporting, positive = consuming/importing (see CLAUDE.md). `available` in the strategy = `chargerPower + batteryPower - gridPower`.
- Single-phase 230 V conversion is intentional (`amps * 230`); keep it in the strategy only.
- All monotonic timestamps come from `GlobalTimeSource.source.markNow()` (production) or `kotlin.time.TestTimeSource` (tests); never `System.nanoTime()`.

## Setup

- [ ] `cd /home/koen/Code/ems-server && git checkout -b fix/charging-path-review`

---

### Task 1: OCPP reconnect race — a stale connection close must not evict the live session

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt:105-109` (`unregisterSession`)
- Modify: `src/main/kotlin/ocpp/OcppServer.kt:55` (caller)
- Test: `src/test/kotlin/ocpp/OcppServiceTest.kt`

**Interfaces:**
- Produces: `suspend fun unregisterSession(chargePointId: String, session: DefaultWebSocketSession)` — new second parameter. All callers must pass the closing WS session.

- [ ] **Step 1: Write the failing test** (append to `OcppServiceTest`)

```kotlin
@Test
fun `stale connection close does not evict a newer session`() = runTest {
    val svc = newService()
    val oldWs = mockk<DefaultWebSocketSession>(relaxed = true)
    val newWs = mockk<DefaultWebSocketSession>(relaxed = true)
    svc.registerSession("CP1", oldWs)
    svc.registerSession("CP1", newWs)   // charger reconnected while the old socket lingers
    svc.unregisterSession("CP1", oldWs) // the old connection finally times out
    assertNotNull(svc.getSession("CP1"), "live session must survive the stale close")
    svc.unregisterSession("CP1", newWs)
    assertNull(svc.getSession("CP1"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: compile error (`unregisterSession` has no 2-arg overload). That counts as the failing state.

- [ ] **Step 3: Implement**

In `OcppService.kt` replace `unregisterSession`:

```kotlin
suspend fun unregisterSession(chargePointId: String, session: DefaultWebSocketSession) {
    // Only evict the session this connection registered: on a quick reconnect the new
    // connection has already replaced the map entry, and the old handler's close must
    // not remove it — that would leave a connected charger invisible to the EMS.
    val current = sessions[chargePointId]
    if (current?.session !== session) {
        logger.info("Ignoring close of a superseded connection for $chargePointId")
        return
    }
    sessions.remove(chargePointId, current)
    logger.info("Unregistered charge point $chargePointId")
    recomputeState()
}
```

In `OcppServer.kt`, `handleConnection`'s `finally` block becomes:

```kotlin
} finally {
    service.unregisterSession(chargePointId, session)
    log.info("OCPP connection closed for {cp}", chargePointId)
}
```

Then `grep -rn "unregisterSession" src/` and fix any other caller the same way (tests included).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest" --tests "io.konektis.ocpp.OcppServerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(ocpp): stale connection close no longer evicts a newer live session"
```

---

### Task 2: Restore persisted capabilities on register (reconnect without BootNotification)

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt:99-103` (`registerSession`)
- Test: `src/test/kotlin/ocpp/OcppServiceTest.kt`

**Interfaces:**
- Consumes: `ChargePointStore.get(id)` (existing).
- Produces: no signature change; `registerSession` becomes DB-reading.

- [ ] **Step 1: Write the failing test** (append to `OcppServiceTest`)

```kotlin
@Test
fun `reconnect without BootNotification restores persisted capabilities`() = runTest {
    val db = freshTestDb()
    val svcA = newServiceOn(db)
    svcA.registerSession("CP1", mockk(relaxed = true))
    svcA.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1"))
    svcA.applyCapabilityProbe("CP1", GetConfigurationResponse(
        configurationKey = listOf(ConfigurationKey("SupportedFeatureProfiles", true, "Core,SmartCharging"))))
    assertTrue(svcA.isPowerControlCapable("CP1"))

    // Server restart: the charger reconnects but sends no BootNotification
    // (OCPP 1.6 only requires one per charger reboot).
    val svcB = newServiceOn(db)
    svcB.registerSession("CP1", mockk(relaxed = true))
    assertTrue(svcB.isPowerControlCapable("CP1"),
        "SmartCharging capability must survive a reconnect without boot")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: FAIL on the second `assertTrue` (capability is `false`).

- [ ] **Step 3: Implement** — replace `registerSession`:

```kotlin
suspend fun registerSession(chargePointId: String, session: DefaultWebSocketSession) {
    // Restore persisted facts: a charge point reconnecting without a fresh BootNotification
    // (OCPP only requires one per reboot) must not lose its SmartCharging capability — the
    // EMS would keep opening transactions but silently stop sending charging profiles.
    val persisted = chargePoints.get(chargePointId)
    sessions[chargePointId] = ChargePointSession(chargePointId, session).apply {
        if (persisted != null) {
            vendor = persisted.vendor
            model = persisted.model
            smartChargingSupported = persisted.smartChargingSupported
            powerImportSeen = persisted.powerImportSeen
        }
    }
    logger.info("Registered charge point $chargePointId")
    recomputeState()
}
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(ocpp): restore persisted SmartCharging capability on reconnect without boot"
```

---

### Task 3: StartTransaction with a rejected idTag must not open a live transaction

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt:148-160` (`handleStartTransaction`)
- Test: `src/test/kotlin/ocpp/OcppServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `rejected idTag does not open a live transaction`() = runTest {
    val svc = newService(acceptTags = false) // unknown tags are rejected
    svc.registerSession("CP1", mockk(relaxed = true))
    val resp = svc.handleStartTransaction("CP1",
        StartTransactionRequest(connectorId = 1, idTag = "UNKNOWN", meterStart = 0, timestamp = "2026-01-01T00:00:00Z"))
    assertEquals(AuthorizationStatus.Invalid, resp.idTagInfo.status)
    assertNull(svc.activeTransactionId("CP1", 1),
        "a rejected transaction must not be tracked as charging")
}
```

- [ ] **Step 2: Run** — Expected: FAIL (`activeTransactionId` returns the new id).

- [ ] **Step 3: Implement** — replace `handleStartTransaction`:

```kotlin
suspend fun handleStartTransaction(chargePointId: String, request: StartTransactionRequest): StartTransactionResponse {
    val transactionId = transactionIdCounter.getAndIncrement()
    val auth = authorizeTag(request.idTag)
    // Per OCPP 1.6 a transactionId is always returned, but a non-Accepted idTagInfo means the
    // charge point must not deliver energy — so don't track it as a live charging session either.
    if (auth == AuthorizationStatus.Accepted) {
        sessions[chargePointId]?.let { s ->
            s.activeTransactions[transactionId] =
                ActiveTransaction(transactionId, request.connectorId, request.idTag, Instant.now(), request.meterStart)
            s.connectors.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }.apply {
                currentTransactionId = transactionId
                status = ChargePointStatus.Charging
            }
        }
        recomputeState()
    }
    return StartTransactionResponse(transactionId, IdTagInfo(status = auth))
}
```

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"` — Expected: PASS (existing start/stop tests use accepted tags and stay green).

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(ocpp): do not track a live transaction when the idTag is rejected"
```

---

### Task 4: Timestamped power readings + MeterValues staleness in OcppCharger

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt` (`ConnectorState`, `handleMeterValues`, `latestPowerW`, `recomputeState`, new `latestPowerReading`)
- Modify: `src/main/kotlin/devices/charger/OcppCharger.kt` (`getState`, constructor)
- Test: `src/test/kotlin/ocpp/OcppServiceTest.kt`, `src/test/kotlin/devices/charger/OcppChargerTest.kt`

**Interfaces:**
- Produces: `data class PowerReading(val watts: Int, val at: ComparableTimeMark)` in `io.konektis.ocpp` (OcppService.kt).
- Produces: `fun latestPowerReading(chargePointId: String, connectorId: Int): PowerReading?` on `OcppService`. `latestPowerW` stays (web UI) delegating to it.
- Produces: `OcppCharger` constructor gains `meterStaleAfter: Duration = 90.seconds` (named param, after `idTag`).

- [ ] **Step 1: Write the failing tests**

Append to `OcppServiceTest`:

```kotlin
@Test
fun `meterValues power readings carry a timestamp`() = runTest {
    val svc = newService()
    svc.registerSession("CP1", mockk(relaxed = true))
    svc.handleMeterValues("CP1", MeterValuesRequest(connectorId = 1, meterValue = listOf(
        MeterValue("t", listOf(SampledValue(value = "2300", measurand = Measurand.PowerActiveImport, unit = UnitOfMeasure.W))))))
    val reading = svc.latestPowerReading("CP1", 1)
    assertEquals(2300, reading?.watts)
    assertTrue(reading!!.at.elapsedNow() < 5.seconds)
}
```

(add `import kotlin.time.Duration.Companion.seconds` to the test file)

Append to `OcppChargerTest`:

```kotlin
@Test
fun `getState treats a frozen MeterValues reading as unreadable mid-transaction`() = runTest {
    val ts = TestTimeSource()
    val staleMark = ts.markNow()
    ts += 120.seconds // older than the 90 s meter-staleness bound
    val svc = mockk<OcppService>()
    every { svc.latestPowerReading("CP1", 1) } returns PowerReading(7000, staleMark)
    every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Charging
    every { svc.activeTransactionId("CP1", 1) } returns 7
    val charger = OcppCharger("CP1", 1, svc)
    assertNull(charger.getState(),
        "a charger frozen mid-transaction must read as unreadable, not as the last value forever")
}
```

(add imports `io.konektis.ocpp.PowerReading`, `kotlin.time.TestTimeSource`, `kotlin.time.Duration.Companion.seconds`)

- [ ] **Step 2: Run to verify failure** — compile errors for `PowerReading`/`latestPowerReading`. Expected.

- [ ] **Step 3: Implement**

`OcppService.kt` — add next to `ConnectorState` (import `kotlin.time.ComparableTimeMark`, `io.konektis.GlobalTimeSource`):

```kotlin
/** A power sample plus the monotonic moment it arrived, for staleness checks. */
data class PowerReading(val watts: Int, val at: ComparableTimeMark)
```

`ConnectorState`: replace `var lastPowerW: Int? = null` with `var lastPower: PowerReading? = null`.

`handleMeterValues`: replace `connector?.lastPowerW = powerW` with:

```kotlin
connector?.lastPower = PowerReading(powerW, GlobalTimeSource.source.markNow())
```

Accessors:

```kotlin
/** Latest active-power reading (W) for a connector, or null until one arrives. */
fun latestPowerW(chargePointId: String, connectorId: Int): Int? =
    latestPowerReading(chargePointId, connectorId)?.watts

/** Latest active-power reading with its arrival time, for staleness checks. */
fun latestPowerReading(chargePointId: String, connectorId: Int): PowerReading? =
    sessions[chargePointId]?.connectors?.get(connectorId)?.lastPower
```

`recomputeState`: `OcppConnectorView(c.connectorId, c.status.name, c.lastPower?.watts, c.currentTransactionId)`.

`OcppCharger.kt` — constructor and `getState`:

```kotlin
class OcppCharger(
    private val chargePointId: String,
    private val connectorId: Int,
    private val service: OcppService,
    private val idTag: String = OcppService.EMS_ID_TAG,
    private val meterStaleAfter: Duration = 90.seconds,
) : Charger, Klogging {
```

```kotlin
override suspend fun getState(): DeviceUpdate<ChargerState>? {
    val reading = service.latestPowerReading(chargePointId, connectorId)
    val connection = chargerConnectionFrom(service.connectorStatus(chargePointId, connectorId))
    // Return a state when we know either the power or the connection; only bail when both are absent
    // (so a connected-but-idle car still surfaces before any MeterValue arrives).
    if (reading == null && connection == ChargerConnection.Unknown) return null
    val txActive = service.activeTransactionId(chargePointId, connectorId) != null
    return when {
        // No transaction: the charger draws nothing; a leftover reading from the previous
        // session must not linger on the app's main screen.
        !txActive -> DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(0), connection))
        // Transaction just opened, no MeterValue yet: report 0 W rather than nothing.
        reading == null -> DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(0), connection))
        // MeterValues stopped flowing mid-transaction (charger hiccup): the last value is
        // NOT current draw. Report unreadable so the EMS degrades instead of steering on it.
        reading.at.elapsedNow() > meterStaleAfter -> null
        else -> DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(reading.watts), connection))
    }
}
```

(imports: `kotlin.time.Duration`, `kotlin.time.Duration.Companion.seconds`)

Update the existing `OcppChargerTest` mocks: every `every { svc.latestPowerW("CP1", 1) } returns X` becomes
`every { svc.latestPowerReading("CP1", 1) } returns X?.let { PowerReading(it, GlobalTimeSource.source.markNow()) }` — concretely:
- `returns null` → `returns null`
- `returns 2300` → `returns PowerReading(2300, GlobalTimeSource.source.markNow())`
- `returns 7000` → `returns PowerReading(7000, GlobalTimeSource.source.markNow())`

(add import `io.konektis.GlobalTimeSource` to the test file)

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest" --tests "io.konektis.devices.charger.OcppChargerTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(charger): treat frozen OCPP MeterValues as unreadable instead of steering on stale power"
```

---

### Task 5: OcppCharger command dedup, RemoteStart backoff, no start on Unknown connection

**Files:**
- Modify: `src/main/kotlin/devices/charger/OcppCharger.kt` (`apply`, `ensureTransactionStarted`)
- Test: `src/test/kotlin/devices/charger/OcppChargerTest.kt`

**Interfaces:**
- Produces: `OcppCharger` constructor gains `profileRefresh: Duration = 60.seconds`, `startRetryBackoff: Duration = 30.seconds` (after `meterStaleAfter`).

- [ ] **Step 1: Write the failing tests** (append to `OcppChargerTest`)

```kotlin
@Test
fun `unchanged amps are not resent every tick`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.activeTransactionId("CP1", 1) } returns 7
    every { svc.isPowerControlCapable("CP1") } returns true
    coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
    val charger = OcppCharger("CP1", 1, svc)

    charger.apply(ChargerCommand.Charge(Ampere(16)))
    charger.apply(ChargerCommand.Charge(Ampere(16)))

    coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
}

@Test
fun `changed amps are sent immediately`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.activeTransactionId("CP1", 1) } returns 7
    every { svc.isPowerControlCapable("CP1") } returns true
    coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
    val charger = OcppCharger("CP1", 1, svc)

    charger.apply(ChargerCommand.Charge(Ampere(16)))
    charger.apply(ChargerCommand.Charge(Ampere(10)))

    coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    coVerify(exactly = 1) { svc.setChargingProfile("CP1", 1, 10.0, ChargingRateUnitType.A) }
}

@Test
fun `rejected profile is retried on the next tick`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.activeTransactionId("CP1", 1) } returns 7
    every { svc.isPowerControlCapable("CP1") } returns true
    coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returnsMany listOf(false, true)
    val charger = OcppCharger("CP1", 1, svc)

    charger.apply(ChargerCommand.Charge(Ampere(16)))
    charger.apply(ChargerCommand.Charge(Ampere(16)))

    coVerify(exactly = 2) { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
}

@Test
fun `rejected RemoteStart is not retried within the backoff window`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.activeTransactionId("CP1", 1) } returns null
    every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Preparing
    every { svc.isPowerControlCapable("CP1") } returns true
    coEvery { svc.remoteStart("CP1", "EMS", 1) } returns false
    coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
    val charger = OcppCharger("CP1", 1, svc)

    charger.apply(ChargerCommand.Charge(Ampere(16)))
    charger.apply(ChargerCommand.Charge(Ampere(16))) // next 5 s tick, still inside 30 s backoff

    coVerify(exactly = 1) { svc.remoteStart("CP1", "EMS", 1) }
}

@Test
fun `no RemoteStart on an Unknown (faulted) connector`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.activeTransactionId("CP1", 1) } returns null
    every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Faulted
    every { svc.isPowerControlCapable("CP1") } returns true
    coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
    val charger = OcppCharger("CP1", 1, svc)

    charger.apply(ChargerCommand.Charge(Ampere(16)))

    coVerify(exactly = 0) { svc.remoteStart(any(), any(), any()) }
}
```

- [ ] **Step 2: Run to verify failure** — dedup test fails with 2 calls, backoff test with 2 calls, Faulted test with 1 call.

- [ ] **Step 3: Implement** — in `OcppCharger`:

```kotlin
class OcppCharger(
    private val chargePointId: String,
    private val connectorId: Int,
    private val service: OcppService,
    private val idTag: String = OcppService.EMS_ID_TAG,
    private val meterStaleAfter: Duration = 90.seconds,
    private val profileRefresh: Duration = 60.seconds,
    private val startRetryBackoff: Duration = 30.seconds,
) : Charger, Klogging {

    // Last profile the charge point ACCEPTED, to avoid re-sending an identical limit every
    // 5 s tick (log noise, flash wear, and some chargers rate-limit). Refreshed periodically
    // anyway so a charger that lost its profile (e.g. rebooted) recovers within a minute.
    private var lastProfileAmps: Int? = null
    private var lastProfileAt: ComparableTimeMark? = null
    private var lastStartAttemptAt: ComparableTimeMark? = null
```

`apply` Charge branch (Stop branch unchanged):

```kotlin
is ChargerCommand.Charge -> {
    ensureTransactionStarted()
    if (!service.isPowerControlCapable(chargePointId)) {
        logger.debug { "OcppCharger $chargePointId: SmartCharging unsupported, skipping setChargingProfile" }
        return
    }
    val amps = cmd.current.value
    val refreshDue = lastProfileAt?.let { it.elapsedNow() >= profileRefresh } ?: true
    if (amps == lastProfileAmps && !refreshDue) return
    val ok = service.setChargingProfile(chargePointId, connectorId, amps.toDouble(), ChargingRateUnitType.A)
    if (ok) {
        lastProfileAmps = amps
        lastProfileAt = GlobalTimeSource.source.markNow()
    } else {
        lastProfileAmps = null // retry on the next tick
        logger.warn { "OcppCharger $chargePointId: SetChargingProfile($amps A) not accepted" }
    }
}
```

`ensureTransactionStarted`:

```kotlin
/** Start a transaction when a car is plugged in but none is open yet. Idempotent across ticks. */
private suspend fun ensureTransactionStarted() {
    if (service.activeTransactionId(chargePointId, connectorId) != null) return
    val connection = chargerConnectionFrom(service.connectorStatus(chargePointId, connectorId))
    // Only when we positively know a car is there: Unknown covers Faulted connectors and
    // missing status, where a RemoteStart would just be rejected every tick.
    if (connection != ChargerConnection.Connected && connection != ChargerConnection.Charging) return
    // One attempt per backoff window: a charger that rejects RemoteStart (car full, local
    // auth, ...) must not be hammered every 5 s tick.
    if (lastStartAttemptAt?.let { it.elapsedNow() < startRetryBackoff } == true) return
    lastStartAttemptAt = GlobalTimeSource.source.markNow()
    logger.info { "OcppCharger $chargePointId: car connected, no transaction open — RemoteStartTransaction" }
    val ok = service.remoteStart(chargePointId, idTag, connectorId)
    if (!ok) logger.warn { "OcppCharger $chargePointId: RemoteStartTransaction not accepted" }
}
```

(import `kotlin.time.ComparableTimeMark`)

Note: the existing test `positive amps starts a transaction when a car is connected but none is open` still passes (Preparing → Connected, first attempt has no backoff).

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.devices.charger.OcppChargerTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(charger): dedup SetChargingProfile, back off rejected RemoteStart, skip start on faulted connector"
```

---

### Task 6: Solar-session hysteresis in SurplusPriorityStrategy

**Files:**
- Modify: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`
- Test: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`

**Interfaces:**
- Produces: `SurplusPriorityStrategy(gain, gridDeadbandW, startAfterTicks: Int = 12, stopAfterTicks: Int = 60)`.
- Semantics: solar mode (override == null) starts a charging session only after `startAfterTicks` consecutive ticks with `available >= chargerMinAmps*230` (~60 s at 5 s cadence), and stops it after `stopAfterTicks` consecutive ticks with `available < minW/2` (~5 min). While no session is active it emits `ChargerCommand.Stop`. Overrides sync the session flag (`Charge` → active, `Stop` → inactive).

- [ ] **Step 1: Rewrite the affected tests and add new ones**

In `SurplusPriorityStrategyTest`, add a helper below the `snapshot(...)` function:

```kotlin
/** A strategy whose solar session is already active (surplus held for the whole start window). */
private fun activeSessionStrategy(stopAfterTicks: Int = 60): SurplusPriorityStrategy =
    SurplusPriorityStrategy(startAfterTicks = 1, stopAfterTicks = stopAfterTicks).also {
        it.decide(snapshot(gridPower = -5000))
    }
```

Update these existing tests to call `activeSessionStrategy()` instead of the shared `strategy` field (the shared `private val strategy = SurplusPriorityStrategy()` field stays for the override/degraded/battery/heatpump tests):

- `large solar surplus — charger gets max amps, battery absorbs the measured export` → `val decisions = activeSessionStrategy().decide(snapshot(gridPower = -2000, chargerPower = 2000))`
- `importing from grid — charger reduces and battery covers the measured import` → `activeSessionStrategy().decide(...)`
- `surplus below charger minimum — charger held at minimum during the session` → `activeSessionStrategy().decide(...)`
- `zero solar and importing — charger held at minimum, battery covers the measured deficit` → `activeSessionStrategy().decide(...)`
- `charger surplus clamped to max amps` → `activeSessionStrategy().decide(...)`
- `holds current battery power when the grid is balanced within the deadband` → `activeSessionStrategy().decide(...)`
- `no surplus during an active solar session still holds the charger at minimum` → `activeSessionStrategy().decide(...)`

Append new tests:

```kotlin
@Test
fun `solar session does not start without sustained surplus`() {
    val s = SurplusPriorityStrategy(startAfterTicks = 3)
    // available = 2000 W >= 6 A * 230 V = 1380 W, but never 3 ticks in a row.
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = -2000)).chargerCommand)
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = -2000)).chargerCommand)
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = 0)).chargerCommand) // lull resets
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = -2000)).chargerCommand)
}

@Test
fun `solar session starts after sustained surplus`() {
    val s = SurplusPriorityStrategy(startAfterTicks = 3)
    repeat(2) { assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = -2000)).chargerCommand) }
    // Third consecutive surplus tick opens the session: 2000/230 = 8 A.
    assertEquals(ChargerCommand.Charge(Ampere(8)), s.decide(snapshot(gridPower = -2000)).chargerCommand)
}

@Test
fun `solar session stops only after a sustained deficit`() {
    val s = activeSessionStrategy(stopAfterTicks = 3)
    // available = 0 < 690 W (minW/2): two deficit ticks still hold the 6 A floor.
    repeat(2) { assertEquals(ChargerCommand.Charge(Ampere(6)), s.decide(snapshot(gridPower = 0)).chargerCommand) }
    // Third consecutive deficit tick closes the session.
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = 0)).chargerCommand)
}

@Test
fun `brief cloud dip does not stop the session`() {
    val s = activeSessionStrategy(stopAfterTicks = 3)
    s.decide(snapshot(gridPower = 0)) // dip tick 1
    s.decide(snapshot(gridPower = 0)) // dip tick 2
    // Sun returns before the stop window elapses: counter resets, session continues.
    assertEquals(ChargerCommand.Charge(Ampere(8)), s.decide(snapshot(gridPower = -2000)).chargerCommand)
    assertEquals(ChargerCommand.Charge(Ampere(6)), s.decide(snapshot(gridPower = 0)).chargerCommand)
}

@Test
fun `Stop override resets the solar session`() {
    val s = activeSessionStrategy()
    s.decide(snapshot(gridPower = -5000, chargerOverride = ChargerCommand.Stop)) // car unplugged / charging off
    // Back in solar mode: the start window must be re-earned, not resumed at the floor.
    assertEquals(ChargerCommand.Stop, s.decide(snapshot(gridPower = 0)).chargerCommand)
}

@Test
fun `Charge override marks the session active so solar resumes without the start window`() {
    val s = SurplusPriorityStrategy(startAfterTicks = 12, stopAfterTicks = 60)
    s.decide(snapshot(gridPower = 0, chargerOverride = ChargerCommand.Charge(Ampere(10)))) // FIXED mode tick
    // Switch to solar: the open session continues at the floor instead of stopping for a minute.
    assertEquals(ChargerCommand.Charge(Ampere(6)), s.decide(snapshot(gridPower = 0)).chargerCommand)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"` — new tests FAIL (compile error on `startAfterTicks`).

- [ ] **Step 3: Implement** — replace `SurplusPriorityStrategy.kt` body:

```kotlin
private const val VOLTAGE = 230 // single-phase installation; the only amps<->watts site

class SurplusPriorityStrategy(
    private val gain: Double = 1.0,
    private val gridDeadbandW: Int = 50,
    private val startAfterTicks: Int = 12, // ~60 s at 5 s cadence
    private val stopAfterTicks: Int = 60,  // ~5 min at 5 s cadence
) : Strategy {

    // Solar-session hysteresis. Without it the min-amps floor means a plugged-in car in solar
    // mode charges at 6 A forever — all night, drained from the home battery. A session opens
    // only after the surplus has covered the charger minimum for a sustained window, and closes
    // only after a sustained deficit (so passing clouds don't chatter the car's contactor).
    private var sessionActive = false
    private var startTicks = 0
    private var stopTicks = 0

    override fun decide(snapshot: WorldSnapshot): ControlDecisions {
        val available = snapshot.chargerPower.value + snapshot.batteryPower.value - snapshot.gridPower.value

        val heatpumpMode: ConsumeMode = if (available >= 0) {
            ConsumeMode.Unrestricted
        } else {
            val headroom = max(0, snapshot.heatpumpPower.value + available)
            ConsumeMode.SuggestConsumeUpTo(Watt(headroom))
        }

        val override = snapshot.chargerOverride
        val chargerCommand: ChargerCommand = if (override != null) {
            // Forced command (Stop / Fixed): sync the session flag so a later switch back to
            // solar starts from reality — unplug resets it, an open fixed session continues.
            sessionActive = override is ChargerCommand.Charge
            startTicks = 0
            stopTicks = 0
            override
        } else {
            solarSessionCommand(available, snapshot.chargerMinAmps, snapshot.chargerMaxAmps)
        }

        val batteryTarget = batteryTarget(snapshot.batteryPower.value, snapshot.gridPower.value)

        return ControlDecisions(
            chargerCommand = chargerCommand,
            batteryCommand = BatteryCommand.SetPower(batteryTarget),
            heatpumpConsumeMode = heatpumpMode
        )
    }

    /**
     * Charger command for an unforced (solar) tick. While a session is active the current tracks
     * the surplus but never drops below the minimum (a car won't charge below it; the shortfall
     * is imported and the battery covers it first). Session start/stop is hysteresis-gated on
     * [startAfterTicks]/[stopAfterTicks] consecutive ticks.
     */
    private fun solarSessionCommand(available: Int, minAmps: Int, maxAmps: Int): ChargerCommand {
        val minW = minAmps * VOLTAGE
        if (!sessionActive) {
            startTicks = if (available >= minW) startTicks + 1 else 0
            if (startTicks >= startAfterTicks) {
                sessionActive = true
                startTicks = 0
                stopTicks = 0
            }
        } else {
            stopTicks = if (available < minW / 2) stopTicks + 1 else 0
            if (stopTicks >= stopAfterTicks) {
                sessionActive = false
                startTicks = 0
                stopTicks = 0
            }
        }
        if (!sessionActive) return ChargerCommand.Stop
        return ChargerCommand.Charge(Ampere((available / VOLTAGE).coerceIn(minAmps, maxAmps)))
    }

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        batteryTarget(batteryPower.value, gridPower.value)

    /**
     * New battery setpoint = current power minus the correction toward grid = 0, ignoring imbalances
     * inside the deadband. Positive = charge, negative = discharge.
     */
    private fun batteryTarget(batteryPower: Int, gridImbalance: Int): Watt {
        val error = if (abs(gridImbalance) <= gridDeadbandW) 0 else gridImbalance
        return Watt(batteryPower - (gain * error).roundToInt())
    }
}
```

Keep the existing class KDoc about the deadbeat battery loop; extend it with one paragraph naming the hysteresis (start: `available >= minAmps*230` for `startAfterTicks`; stop: `available < minW/2` for `stopAfterTicks`).

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest" --tests "io.konektis.ems.EnergyManagerTest"` — Expected: PASS (EnergyManagerTest's tier-1 test only asserts the battery command).

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "feat(ems): surplus-gated start/stop hysteresis for solar charging sessions"
```

---

### Task 7: Blind-tier charger stop + hot-reload-aware chargerKey

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt` (blind branch of `tick()`, `chargerKey`)
- Test: `src/test/kotlin/ems/EnergyManagerTest.kt`

- [ ] **Step 1: Write the failing test** (append to `EnergyManagerTest`)

```kotlin
@Test fun `blind stops a solar-mode charger from the release tick onward`() = runTest {
    val ch = charger(0)
    val world = World(grid(null), ch, emptyMap(), null, battery(0))
    val m = manager(world)
    repeat(5) { m.tick() }
    coVerify(exactly = 0) { ch.apply(any()) } // solar mode: uncommanded while briefly blind
    m.tick() // 6th blind tick — battery released AND charger stopped
    coVerify(exactly = 1) { ch.apply(ChargerCommand.Stop) }
}
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL, `ch.apply(Stop)` never called.

- [ ] **Step 3: Implement**

In `tick()`, blind branch:

```kotlin
// Tier 3 — blind: grid or battery reading missing. Fail toward the inverter.
emsState.gridPower == null || emsState.batteryPower == null -> {
    blindTicks++
    if (blindTicks >= BLIND_RELEASE_TICKS) {
        logger.warn("Blind for $blindTicks ticks (>= $BLIND_RELEASE_TICKS) — releasing battery to inverter")
        world.battery?.let { battery ->
            runCatchingLog("release battery") { battery.releaseToInverter() }
        }
        // A solar-mode charger (override == null) would otherwise keep its last commanded
        // current indefinitely — with no grid data the surplus is unknowable, so stop it.
        if (override == null) applyChargerCommand(ChargerCommand.Stop)
    }
    // Stop/Fixed are still enforced without surplus data; solar surplus (null) is left as-is.
    override?.let { applyChargerCommand(it) }
}
```

And make `chargerKey` follow config hot-reloads:

```kotlin
private val chargerKey: String?
    get() = config.devices.charger.firstOrNull()?.let { it.chargePointId ?: it.name }
```

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(ems): stop a solar-mode charger on blind release; chargerKey follows config hot-reload"
```

---

### Task 8: Web-UI manual start/stop/set-current set EMS intent instead of fighting the loop

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppWebUi.kt` (start/stop/set-current routes; delete `StartBody`, `StopBody`)
- Modify: `src/main/resources/ocpp.html` (`startTx`, `stopTx` functions)
- Test: `src/test/kotlin/ocpp/OcppWebUiTest.kt`

**Interfaces:**
- Consumes: `EnergyManager.setCharging(ChargerControl)`, `EnergyManager.chargerControlFlow` (existing).
- `reset` and `clear-profile` stay direct OCPP commands (genuine device-maintenance actions).

- [ ] **Step 1: Write the failing tests**

In `OcppWebUiTest`, change `testModule()` to also expose the EnergyManager:

```kotlin
private fun Application.testModule(): Pair<OcppService, EnergyManager> {
    // ... unchanged body ...
    configureOcppWebUi(svc, em)
    return svc to em
}
```

Update existing callers (`svc = testModule()` → `svc = testModule().first`). Append:

```kotlin
@Test
fun manualStartAndStopSetTheChargingIntent() = testApplication {
    lateinit var em: EnergyManager
    application { em = testModule().second }
    startApplication()

    val stop = client.post("/ocpp-ui/api/chargepoints/CP1/stop") {
        basicAuth("admin", "secret"); contentType(ContentType.Application.Json); setBody("{}")
    }
    assertEquals(HttpStatusCode.OK, stop.status)
    assertFalse(em.chargerControlFlow.value.charging)

    val start = client.post("/ocpp-ui/api/chargepoints/CP1/start") {
        basicAuth("admin", "secret"); contentType(ContentType.Application.Json); setBody("{}")
    }
    assertEquals(HttpStatusCode.OK, start.status)
    assertTrue(em.chargerControlFlow.value.charging)
}

@Test
fun setCurrentSwitchesToFixedMode() = testApplication {
    lateinit var em: EnergyManager
    application { em = testModule().second }
    startApplication()

    val resp = client.post("/ocpp-ui/api/chargepoints/CP1/set-current") {
        basicAuth("admin", "secret"); contentType(ContentType.Application.Json)
        setBody("""{"amps":10.0,"connectorId":1}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    assertEquals(io.konektis.ChargerMode.FIXED, em.chargerControlFlow.value.mode)
    assertEquals(10, em.chargerControlFlow.value.fixedAmps)
    assertTrue(em.chargerControlFlow.value.charging)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest"` — Expected: FAIL (start/stop return BadGateway — no OCPP session; intent unchanged).

- [ ] **Step 3: Implement**

In `OcppWebUi.kt` replace the three routes (and delete the now-unused `StartBody`, `StopBody` data classes):

```kotlin
// Manual actions set the EMS *intent* (persisted ChargerControl) rather than sending raw
// OCPP commands: the 5 s control loop reasserts intent every tick, so a direct command
// would be overridden within seconds. The EMS opens/closes the transaction on its next tick.
post("/chargepoints/{id}/start") {
    energyManager.setCharging(energyManager.chargerControlFlow.value.copy(charging = true))
    call.respond(HttpStatusCode.OK)
}
post("/chargepoints/{id}/stop") {
    energyManager.setCharging(energyManager.chargerControlFlow.value.copy(charging = false))
    call.respond(HttpStatusCode.OK)
}
// Manual current: switch to FIXED at that current (0 A = stop charging).
post("/chargepoints/{id}/set-current") {
    val body = call.receive<SetCurrentBody>()
    val amps = body.amps.toInt()
    energyManager.setCharging(ChargerControl(ChargerMode.FIXED, amps, charging = amps > 0))
    call.respond(HttpStatusCode.OK)
}
```

In `ocpp.html`:
- `startTx`: remove the `prompt('idTag to start with?')` line and the `idTag`/`connectorId` body fields; send `body: '{}'`.
- `stopTx`: remove the `activeTx` guard/alert and send `body: '{}'` (keep the button enable/disable logic — it is still a useful live indicator).

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "fix(ocpp-ui): manual start/stop/set-current set EMS intent instead of racing the control loop"
```

---

### Task 9: Typed battery SoC in DeviceHealth (kill the "% SoC" string parse)

**Files:**
- Modify: `src/main/kotlin/StatusState.kt` (`DeviceHealth.Online`)
- Modify: `src/main/kotlin/DataCollector.kt:59-65,83`
- Test: Create `src/test/kotlin/DataCollectorSocTest.kt`

**Interfaces:**
- Produces: `DeviceHealth.Online(lastSeenAt, powerW, extraInfo, batterySoc: Int? = null)` — additive, serialization-compatible.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.konektis

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataCollectorSocTest {

    @Test
    fun `battery SoC travels as a typed field, not a display string`() = runTest {
        val grid = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returns
                DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(0), Volt(230u)))
        }
        val battery = mockk<Battery>(relaxed = true).also {
            coEvery { it.getState() } returns
                DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(73u, Watt(500)))
        }
        val collector = DataCollector(1, World(grid, null, emptyMap(), null, battery))
        collector.refresh()
        val status = collector.statusStateFlow.value!!
        assertEquals(73, status.batteryCharge)
        val health = status.devices.single { it.name == "battery" }.health as DeviceHealth.Online
        assertEquals(73, health.batterySoc)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests "io.konektis.DataCollectorSocTest"` — Expected: FAIL (no `batterySoc` property → compile error).

- [ ] **Step 3: Implement**

`StatusState.kt`:

```kotlin
data class Online(
    val lastSeenAt: Long,
    val powerW: Int,
    val extraInfo: String? = null,
    val batterySoc: Int? = null,
) : DeviceHealth()
```

`DataCollector.kt` battery poll:

```kotlin
world.battery?.let { battery ->
    jobs.add(async { poll("battery", "battery") {
        battery.update()
        val state = battery.getState() ?: throw Exception("battery returned no data")
        val soc = state.update.charge.toInt()
        DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value,
            "$soc% SoC", batterySoc = soc)
    }})
}
```

And the aggregation line:

```kotlin
val batteryCharge = batteryOnline?.batterySoc
```

- [ ] **Step 4: Run** — `./gradlew test --tests "io.konektis.DataCollectorSocTest" --tests "io.konektis.DataCollectorHealthTest" --tests "io.konektis.StatusPageTest"` — Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src && git commit -m "refactor(collector): carry battery SoC as a typed field instead of parsing the display string"
```

---

### Task 10: Delete dead OCPP protocol types

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppMessages.kt`

- [ ] **Step 1: Verify each candidate is unused**

```bash
grep -rn "OcppMessage\|OcppCall\|OcppCallResult\|OcppCallError\|HeartbeatRequest\|ChangeAvailability\|AvailabilityType\|AvailabilityStatus\|ChangeConfiguration\|ConfigurationStatus\|ClearCache\|UnlockConnector\|UnlockStatus\|GetCompositeSchedule\|RemoteStartTransactionResponse\|RemoteStopTransactionResponse\|RemoteStartStopStatus\|ResetResponse\|ResetStatus\|SetChargingProfileResponse\|ChargingProfileStatus\|ClearChargingProfileResponse\|ClearChargingProfileStatus" src/ --include="*.kt" | grep -v "src/main/kotlin/ocpp/OcppMessages.kt"
```

Expected: no hits. If a name DOES hit (e.g. a test added meanwhile), keep that type and delete the rest.

- [ ] **Step 2: Delete from `OcppMessages.kt`**

- Sealed base + frame classes: `OcppMessage`, `OcppCall`, `OcppCallResult`, `OcppCallError` (frames are built with `buildJsonArray` in `OcppServer`/`OcppService`).
- `HeartbeatRequest` (heartbeat payload is never decoded).
- `ChangeAvailabilityRequest/Response`, `AvailabilityType`, `AvailabilityStatus`.
- `ChangeConfigurationRequest/Response`, `ConfigurationStatus`.
- `ClearCacheRequest/Response`, `ClearCacheStatus`.
- `UnlockConnectorRequest/Response`, `UnlockStatus`.
- `GetCompositeScheduleRequest/Response`, `GetCompositeScheduleStatus`.
- Outbound-response types never decoded (replies are checked via the raw `status` field): `RemoteStartTransactionResponse`, `RemoteStopTransactionResponse`, `RemoteStartStopStatus`, `ResetResponse`, `ResetStatus`, `SetChargingProfileResponse`, `ChargingProfileStatus`, `ClearChargingProfileResponse`, `ClearChargingProfileStatus`.
- `Action` enum entries: `ChangeAvailability`, `ChangeConfiguration`, `ClearCache`, `UnlockConnector`, `GetCompositeSchedule`, `GetDiagnostics`, `DiagnosticsStatusNotification`, `FirmwareStatusNotification`, `UpdateFirmware`, `GetLocalListVersion`, `SendLocalList`, `CancelReservation`, `ReserveNow`. (Keep: `Authorize`, `BootNotification`, `DataTransfer`, `GetConfiguration`, `Heartbeat`, `MeterValues`, `RemoteStartTransaction`, `RemoteStopTransaction`, `Reset`, `StartTransaction`, `StatusNotification`, `StopTransaction`, `ClearChargingProfile`, `SetChargingProfile`, `TriggerMessage`.)

Keep everything the code references: all inbound request/response pairs handled in `OcppMessageHandler.handleCall`, `GetConfigurationRequest/Response`, `ConfigurationKey`, `RemoteStartTransactionRequest`, `RemoteStopTransactionRequest`, `ResetRequest`, `ResetType`, the ChargingProfile family, `SetChargingProfileRequest`, `ClearChargingProfileRequest`, MeterValue family, all `@SerialName`-carrying enums used by `SampledValue`.

Note: an inbound action that was deleted from the enum still gets the same `NotSupported` CALL_ERROR as before — dispatch in `handleCall` matches on explicit names and falls through to `else`.

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew test`
Expected: PASS, no compile errors.

- [ ] **Step 4: Commit**

```bash
git add -A src && git commit -m "refactor(ocpp): delete unused OCPP 1.6 message types (~250 lines dead code)"
```

---

### Task 11: Full verification, docs, wrap-up

- [ ] **Step 1: Full build** — `./gradlew build` — Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Update docs**

- `CLAUDE.md` → Key Quirks: append one bullet:
  `- **Solar charging sessions**: SurplusPriorityStrategy gates session start/stop with hysteresis — start after ~60 s of surplus ≥ min current (6 A × 230 V), stop after ~5 min of sustained deficit (< half the min). A plugged-in car in SOLAR mode no longer charges at the min floor all night.`
- `grep -n "solar\|min\|floor\|start\|stop" docs/adding-an-ocpp-charger.md` — if the doc describes solar-mode behaviour or the manual web-UI actions, update those paragraphs to match (intent-based start/stop, hysteresis).

- [ ] **Step 3: Commit docs**

```bash
git add CLAUDE.md docs && git commit -m "docs: document solar-session hysteresis and intent-based manual charger actions"
```

- [ ] **Step 4: Report** — summarize per-fix outcome; note that merge to main and NAS deployment are separate user decisions.
