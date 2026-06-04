# Charger Car-Connection Flow + Control Wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive the app's Charger tab from real OCPP car-connection state (no-car / connected-idle / charging) and make the Solar/Fixed/Stop controls actually steer the charger server-side, independent of the global AUTO/MANUAL switch.

**Architecture:** The server already tracks OCPP connector status in `OcppService` and steers the charger via `SetChargingProfile`. We surface a derived `ChargerConnection` into `StatusState` (consumed by the app over `/status-ws`), and add a per-charger control intent (`ChargingState`) to `EnergyManager` that is honoured every tick — flowing through the surplus strategy in AUTO (so the battery's deadbeat math stays exact) and applied to the charger leg alone in MANUAL. The app gains a pure `chargerUiState(connection)` mapping and renders three states.

**Tech Stack:** Kotlin/Ktor, kotlinx-serialization (polymorphic sealed `Message`/`ChargingState`), MockK + kotlinx-coroutines-test (server tests), Jetpack Compose + JUnit (app tests). Build: `./gradlew build` (server, repo root); `cd android && ./gradlew :app:testDebugUnitTest` / `:app:assembleDebug` (app).

**Spec:** `docs/superpowers/specs/2026-06-04-charger-connection-flow-design.md`

---

## File Structure

**Server (`src/main/kotlin/`)**
- `devices/charger/Charger.kt` — add `ChargerConnection` enum; add `connection` field (default `Unknown`) to `ChargerState`.
- `ocpp/OcppService.kt` — add `connectorStatus(chargePointId, connectorId)`.
- `devices/charger/OcppCharger.kt` — add `chargerConnectionFrom(...)` mapper; surface connection in `getState()`.
- `StatusState.kt` — add `chargerConnection: String?` (default null).
- `DataCollector.kt` — capture the charger's connection into the emitted `StatusState`.
- `ems/Strategy.kt` — add `chargerOverrideAmps: Int?` (default null) to `WorldSnapshot`.
- `ems/SurplusPriorityStrategy.kt` — honour the override on the charger leg.
- `ems/EnergyManager.kt` — `chargerControl` + `setCharging()` + `chargingStateFlow`; resolve & apply the charger intent each tick.
- `Messages.kt` — add `Message.ChargingStateUpdate`.
- `Sockets.kt` — handle `ClientMessage.SetCharging`; push `ChargingStateUpdate` on change + on auth.

**App (`android/app/src/main/kotlin/io/konektis/ems/`)**
- `data/model/WsMessage.kt` — add `Message.ChargingStateUpdate`.
- `data/model/StatusState.kt` — add `chargerConnection: String?` (default null).
- `data/model/ChargerConnection.kt` — new: enum + `parseChargerConnection`.
- `data/ws/ControlWsClient.kt` — expose `chargingState: StateFlow<ChargingState?>`.
- `ui/charger/ChargerUiState.kt` — new: pure `chargerUiState(connection)`.
- `ui/charger/ChargerScreen.kt` — three-state rendering.
- `ui/dashboard/DashboardViewModel.kt` — expose `chargingState`.
- `ui/dashboard/DashboardScreen.kt` — pass `chargingState` + `mode` to `ChargerScreen`.
- `ui/NavHost.kt` — wire `chargingState` from `ControlWsClient`.

**Note on Webasto:** `Webasto.getState()` builds `ChargerState(Watt(...))`; with the new `connection` field defaulting to `Unknown`, no Webasto change is needed — non-OCPP chargers report `Unknown` and the app shows the controls fallback.

**Note on i18n:** New user-facing strings in this plan ("No car connected", the MANUAL note) are added as hardcoded literals; the separate i18n spec (`2026-06-04-app-i18n-dutch-design.md`) extracts them later.

---

## Task 1: `ChargerConnection` enum + `ChargerState.connection`

**Files:**
- Modify: `src/main/kotlin/devices/charger/Charger.kt`
- Test: `src/test/kotlin/devices/charger/ChargerStateTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/devices/charger/ChargerStateTest.kt`:

```kotlin
package io.konektis.devices.charger

import io.konektis.devices.Watt
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerStateTest {
    @Test
    fun `connection defaults to Unknown`() {
        assertEquals(ChargerConnection.Unknown, ChargerState(Watt(0)).connection)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.devices.charger.ChargerStateTest"`
Expected: FAIL — `ChargerConnection` / `connection` unresolved (compile error).

- [ ] **Step 3: Add the enum and field**

In `src/main/kotlin/devices/charger/Charger.kt`, replace the `ChargerState` declaration:

```kotlin
package io.konektis.devices.charger

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

/** Whether a car is plugged in / charging, derived from OCPP connector status where available. */
enum class ChargerConnection { NotConnected, Connected, Charging, Unknown }

data class ChargerState(
    val currentPower: Watt,
    val connection: ChargerConnection = ChargerConnection.Unknown
)

interface Charger {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<ChargerState>?
    suspend fun setMaxChargerPower(power: Watt)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.devices.charger.ChargerStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/devices/charger/Charger.kt src/test/kotlin/devices/charger/ChargerStateTest.kt
git commit -m "feat(charger): add ChargerConnection to ChargerState"
```

---

## Task 2: Surface OCPP connector status as `ChargerConnection`

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt` (add `connectorStatus`)
- Modify: `src/main/kotlin/devices/charger/OcppCharger.kt`
- Test: `src/test/kotlin/devices/charger/OcppChargerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/devices/charger/OcppChargerTest.kt` (inside the class). Also note: the two existing tests `getStateReturnsNullUntilPowerSeen` and `getStateReflectsLatestPower` must now stub `connectorStatus`; update them as shown.

```kotlin
import io.konektis.ocpp.ChargePointStatus

@Test
fun `chargerConnectionFrom maps OCPP statuses`() {
    assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Available))
    assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Reserved))
    assertEquals(ChargerConnection.NotConnected, chargerConnectionFrom(ChargePointStatus.Unavailable))
    assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.Preparing))
    assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.SuspendedEV))
    assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.SuspendedEVSE))
    assertEquals(ChargerConnection.Connected, chargerConnectionFrom(ChargePointStatus.Finishing))
    assertEquals(ChargerConnection.Charging, chargerConnectionFrom(ChargePointStatus.Charging))
    assertEquals(ChargerConnection.Unknown, chargerConnectionFrom(ChargePointStatus.Faulted))
    assertEquals(ChargerConnection.Unknown, chargerConnectionFrom(null))
}

@Test
fun `getState surfaces connection even with no power yet`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.latestPowerW("CP1", 1) } returns null
    every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Preparing
    val charger = OcppCharger("CP1", 1, svc)
    charger.update()
    val state = charger.getState()
    assertEquals(0, state?.update?.currentPower?.value)
    assertEquals(ChargerConnection.Connected, state?.update?.connection)
}

@Test
fun `getState reflects charging connection with power`() = runTest {
    val svc = mockk<OcppService>()
    every { svc.latestPowerW("CP1", 1) } returns 7000
    every { svc.connectorStatus("CP1", 1) } returns ChargePointStatus.Charging
    val charger = OcppCharger("CP1", 1, svc)
    charger.update()
    val state = charger.getState()
    assertEquals(7000, state?.update?.currentPower?.value)
    assertEquals(ChargerConnection.Charging, state?.update?.connection)
}
```

Update the two existing tests to stub `connectorStatus` so the no-data case still returns null:

```kotlin
@Test
fun getStateReturnsNullUntilPowerSeen() = runTest {
    val svc = mockk<OcppService>()
    every { svc.latestPowerW("CP1", 1) } returns null
    every { svc.connectorStatus("CP1", 1) } returns null
    val charger = OcppCharger("CP1", 1, svc)
    charger.update()
    assertNull(charger.getState())
}

@Test
fun getStateReflectsLatestPower() = runTest {
    val svc = mockk<OcppService>()
    every { svc.latestPowerW("CP1", 1) } returns 2300
    every { svc.connectorStatus("CP1", 1) } returns null
    val charger = OcppCharger("CP1", 1, svc)
    charger.update()
    assertEquals(2300, charger.getState()?.update?.currentPower?.value)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.devices.charger.OcppChargerTest"`
Expected: FAIL — `connectorStatus` / `chargerConnectionFrom` unresolved.

- [ ] **Step 3: Add `connectorStatus` to `OcppService`**

In `src/main/kotlin/ocpp/OcppService.kt`, next to the existing `latestPowerW` (around line 215), add:

```kotlin
    /** Latest known OCPP connector status, or null if the charge point/connector is unknown. */
    fun connectorStatus(chargePointId: String, connectorId: Int): ChargePointStatus? =
        sessions[chargePointId]?.connectors?.get(connectorId)?.status
```

- [ ] **Step 4: Map and surface connection in `OcppCharger`**

Replace `src/main/kotlin/devices/charger/OcppCharger.kt`'s imports + `getState()` and add the mapper. Add these imports near the top:

```kotlin
import io.konektis.ocpp.ChargePointStatus
```

Add this top-level function (after the imports, before the class):

```kotlin
/** Maps an OCPP connector status to the app-facing ChargerConnection. */
internal fun chargerConnectionFrom(status: ChargePointStatus?): ChargerConnection = when (status) {
    ChargePointStatus.Available,
    ChargePointStatus.Reserved,
    ChargePointStatus.Unavailable -> ChargerConnection.NotConnected
    ChargePointStatus.Preparing,
    ChargePointStatus.SuspendedEV,
    ChargePointStatus.SuspendedEVSE,
    ChargePointStatus.Finishing -> ChargerConnection.Connected
    ChargePointStatus.Charging -> ChargerConnection.Charging
    ChargePointStatus.Faulted, null -> ChargerConnection.Unknown
}
```

Replace `getState()`:

```kotlin
    override suspend fun getState(): DeviceUpdate<ChargerState>? {
        val powerW = service.latestPowerW(chargePointId, connectorId)
        val connection = chargerConnectionFrom(service.connectorStatus(chargePointId, connectorId))
        // Return a state when we know either the power or the connection; only bail when both are absent
        // (so a connected-but-idle car still surfaces before any MeterValue arrives).
        if (powerW == null && connection == ChargerConnection.Unknown) return null
        return DeviceUpdate(
            GlobalTimeSource.source.markNow(),
            ChargerState(Watt(powerW ?: 0), connection)
        )
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.devices.charger.OcppChargerTest"`
Expected: PASS (all, including the two updated ones).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ocpp/OcppService.kt src/main/kotlin/devices/charger/OcppCharger.kt src/test/kotlin/devices/charger/OcppChargerTest.kt
git commit -m "feat(charger): derive ChargerConnection from OCPP connector status"
```

---

## Task 3: Carry `chargerConnection` into `StatusState`

**Files:**
- Modify: `src/main/kotlin/StatusState.kt`
- Modify: `src/main/kotlin/DataCollector.kt`
- Test: `src/test/kotlin/DataCollectorHealthTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/DataCollectorHealthTest.kt`. Add imports:

```kotlin
import io.konektis.devices.charger.Charger
import io.konektis.devices.charger.ChargerConnection
import io.konektis.devices.charger.ChargerState
```

Add the test:

```kotlin
    @Test
    fun `refresh surfaces charger connection in StatusState`() = runTest {
        val charger = mockk<Charger>()
        coEvery { charger.update() } just runs
        coEvery { charger.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            ChargerState(Watt(0), ChargerConnection.Connected)
        )

        val collector = DataCollector(1, makeWorld(chargers = mapOf("Webasto" to charger)))
        collector.refresh()

        assertEquals("Connected", collector.statusStateFlow.value!!.chargerConnection)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.DataCollectorHealthTest"`
Expected: FAIL — `chargerConnection` unresolved on `StatusState`.

- [ ] **Step 3: Add the field to `StatusState`**

In `src/main/kotlin/StatusState.kt`, add a trailing field to the `StatusState` data class:

```kotlin
@Serializable
data class StatusState(
    val devices: List<DeviceStatus>,
    val totalSolarW: Int?,
    val gridW: Int?,
    val batteryW: Int?,
    val batteryCharge: Int?,
    val chargerW: Int?,
    val heatpumpW: Int?,
    val chargerConnection: String? = null
)
```

- [ ] **Step 4: Populate it in `DataCollector`**

In `src/main/kotlin/DataCollector.kt`:

Add a field on the class (next to `healthMap`):

```kotlin
    @Volatile private var chargerConnection: String? = null
```

At the very start of `refresh()`'s `withContext(dispatcher) {` block (before building `jobs`), reset it:

```kotlin
            chargerConnection = null
```

In the `world.chargers.forEach { (name, charger) -> ... }` poll block, capture the connection before returning the health (the existing block becomes):

```kotlin
            world.chargers.forEach { (name, charger) ->
                jobs.add(async { poll(name, "charger") {
                    charger.update()
                    val state = charger.getState() ?: throw Exception("$name returned no data")
                    chargerConnection = state.update.connection.name
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.currentPower.value)
                }})
            }
```

In the `statusStateFlow.value = StatusState(...)` call, add the field:

```kotlin
            statusStateFlow.value = StatusState(
                devices = statuses,
                totalSolarW = totalSolarW,
                gridW = gridW,
                batteryW = batteryW,
                batteryCharge = batteryCharge,
                chargerW = chargerW,
                heatpumpW = heatpumpW,
                chargerConnection = chargerConnection
            )
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.DataCollectorHealthTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/StatusState.kt src/main/kotlin/DataCollector.kt src/test/kotlin/DataCollectorHealthTest.kt
git commit -m "feat(status): include charger connection in StatusState"
```

---

## Task 4: `chargerOverrideAmps` on `WorldSnapshot` + strategy honours it

**Files:**
- Modify: `src/main/kotlin/ems/Strategy.kt`
- Modify: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`
- Test: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`, add a `chargerOverrideAmps` parameter to the `snapshot(...)` helper (add `chargerOverrideAmps: Int? = null` as the last parameter and pass it through to `WorldSnapshot(... chargerOverrideAmps = chargerOverrideAmps)`), then add:

```kotlin
    @Test
    fun `charger override is used verbatim and battery projects from it`() {
        // override 5A (1150W); grid -2000 export, charger 0.
        // projectedGrid = -2000 + 1150 - 0 = -850 -> battery +850W.
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 0, chargerOverrideAmps = 5))
        assertEquals(5, d.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(850)), d.batteryCommand)
    }

    @Test
    fun `charger override zero stops the charger regardless of surplus`() {
        val d = strategy.decide(snapshot(gridPower = -5000, chargerPower = 0, chargerOverrideAmps = 0))
        assertEquals(0, d.chargerMaxAmps)
    }

    @Test
    fun `charger override clamped to max amps`() {
        val d = strategy.decide(snapshot(chargerOverrideAmps = 100, chargerMaxAmps = 32))
        assertEquals(32, d.chargerMaxAmps)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: FAIL — `chargerOverrideAmps` unresolved.

- [ ] **Step 3: Add the field to `WorldSnapshot`**

In `src/main/kotlin/ems/Strategy.kt`, add a trailing field to `WorldSnapshot`:

```kotlin
data class WorldSnapshot(
    val gridPower: Watt,
    val solarPower: Watt,
    val batteryCharge: UShort,
    val batteryPower: Watt,
    val chargerPower: Watt,
    val heatpumpPower: Watt,
    val chargerMinAmps: Int,
    val chargerMaxAmps: Int,
    val chargerOverrideAmps: Int? = null  // non-null = forced charger amps (Stop=0 / Fixed); null = compute from surplus
)
```

- [ ] **Step 4: Honour the override in the strategy**

In `src/main/kotlin/ems/SurplusPriorityStrategy.kt`, replace the `chargerAmps` computation inside `decide(...)`:

```kotlin
        // Car charger: forced override (Stop/Fixed) wins; otherwise assign available surplus.
        val chargerAmps = snapshot.chargerOverrideAmps?.coerceIn(0, snapshot.chargerMaxAmps) ?: when {
            available <= 0 -> 0
            else -> {
                val amps = available / 230
                when {
                    amps < snapshot.chargerMinAmps -> 0
                    amps > snapshot.chargerMaxAmps -> snapshot.chargerMaxAmps
                    else -> amps
                }
            }
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: PASS (all, including the pre-existing surplus tests which use the null default).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ems/Strategy.kt src/main/kotlin/ems/SurplusPriorityStrategy.kt src/test/kotlin/ems/SurplusPriorityStrategyTest.kt
git commit -m "feat(ems): support forced charger amps override in surplus strategy"
```

---

## Task 5: `EnergyManager` charger control intent + tick wiring

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Test: `src/test/kotlin/ems/EnergyManagerTest.kt`

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/ems/EnergyManagerTest.kt`, add the import:

```kotlin
import io.konektis.ChargingState
```

Add tests inside the class:

```kotlin
    @Test fun `setCharging updates chargingStateFlow`() = runTest {
        val world = World(grid(0), mapOf("c" to charger(0)), emptyMap(), emptyMap(), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargingState.NotCharging())
        assertTrue(m.chargingStateFlow.value is ChargingState.NotCharging)
    }

    @Test fun `stop charging forces charger to zero amps in AUTO`() = runTest {
        val ch = charger(0)
        // Strong export would normally give the charger surplus amps; Stop overrides to 0.
        val world = World(grid(-5000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargingState.NotCharging())
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(0)) }
    }

    @Test fun `fixed power sets clamped amps in AUTO`() = runTest {
        val ch = charger(0)
        val world = World(grid(0), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargingState.ChargingWithMaxPower(3680u)) // 16A
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(16 * 230)) }
    }

    @Test fun `excess power follows surplus in MANUAL, battery untouched`() = runTest {
        val ch = charger(0)
        val bat = battery(0)
        // grid -3000 export, charger 0 -> available 3000 -> 13A (2990W).
        val world = World(grid(-3000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to bat))
        val m = manager(world)
        m.setMode(Mode.MANUAL)
        m.setCharging(ChargingState.ChargingWithExcessPower())
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(13 * 230)) }
        // battery is released once on the AUTO->MANUAL transition and never given a SetPower target here
        coVerify(exactly = 0) { bat.setChargingPower(any()) }
    }

    @Test fun `stop charging forces zero in MANUAL`() = runTest {
        val ch = charger(0)
        val world = World(grid(-3000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setMode(Mode.MANUAL)
        m.setCharging(ChargingState.NotCharging())
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(0)) }
    }
```

Add the import for `assertTrue` if missing:

```kotlin
import kotlin.test.assertTrue
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: FAIL — `setCharging` / `chargingStateFlow` unresolved.

- [ ] **Step 3: Add the control state and tick wiring**

In `src/main/kotlin/ems/EnergyManager.kt`:

Add the import:

```kotlin
import io.konektis.ChargingState
```

Add fields after `modeFlow` (around line 31):

```kotlin
    @Volatile
    var chargerControl: ChargingState = ChargingState.ChargingWithExcessPower()
        private set
    val chargingStateFlow = MutableStateFlow<ChargingState>(ChargingState.ChargingWithExcessPower())

    fun setCharging(state: ChargingState) {
        chargerControl = state
        chargingStateFlow.value = state
    }
```

Add these helpers to the class (near `buildWorldSnapshot`):

```kotlin
    /** Max charger amps from config, or null if no charger is configured. */
    private fun configMaxAmps(): Int? =
        config.devices.charger.firstOrNull()?.chargingCurrent?.max?.toInt()

    /** Forced charger amps for Stop (0) / Fixed (clamped); null for ExcessPower (use surplus). */
    private fun chargerOverrideAmps(maxAmps: Int): Int? = when (val c = chargerControl) {
        is ChargingState.NotCharging -> 0
        is ChargingState.ChargingWithMaxPower -> (c.maxPower.toInt() / 230).coerceIn(0, maxAmps)
        is ChargingState.ChargingWithExcessPower -> null
    }

    private suspend fun applyChargerAmps(amps: Int) {
        world.chargers.values.forEach { charger ->
            runCatchingLog("set charger power") { charger.setMaxChargerPower(Watt(amps * 230)) }
        }
    }

    /** MANUAL: battery/heat pump are released; still drive the charger per its intent. */
    private suspend fun applyChargerInManual(emsState: EMSState, override: Int?) {
        val amps = override ?: run {
            val snapshot = buildWorldSnapshot(emsState) ?: return
            strategy.decide(snapshot.copy(chargerOverrideAmps = null)).chargerMaxAmps
        }
        applyChargerAmps(amps)
    }
```

Replace the body of `tick()` from the `if (mode != Mode.AUTO) return` line down to the end of the `when { ... }` with:

```kotlin
        val override = configMaxAmps()?.let { chargerOverrideAmps(it) }

        if (mode != Mode.AUTO) {
            // MANUAL: independent charger control (battery/heat pump already released on transition).
            applyChargerInManual(emsState, override)
            return
        }

        when {
            // Tier 3 — blind: grid or battery reading missing. Fail toward the inverter.
            emsState.gridPower == null || emsState.batteryPower == null -> {
                blindTicks++
                if (blindTicks >= BLIND_RELEASE_TICKS) {
                    logger.warn("Blind for $blindTicks ticks (>= $BLIND_RELEASE_TICKS) — releasing battery to inverter")
                    world.batteries.values.forEach { battery ->
                        runCatchingLog("release battery") { battery.releaseToInverter() }
                    }
                }
                // Stop/Fixed are still enforced without surplus data; ExcessPower (null) is left as-is.
                override?.let { applyChargerAmps(it) }
            }
            // Tier 1 — full data: run the surplus cascade with the charger override folded in.
            emsState.chargerPower != null && emsState.heatpumpPower != null -> {
                blindTicks = 0
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot != null) {
                    val decisions = strategy.decide(snapshot.copy(chargerOverrideAmps = override))
                    logger.debug(
                        "control: grid=${emsState.gridPower}W battery=${emsState.batteryPower}W " +
                            "charger=$override -> ${decisions.batteryCommand} (target >0 = charge, <0 = discharge)"
                    )
                    applyDecisions(decisions)
                }
            }
            // Tier 2 — degraded: grid + battery present; steer the battery only.
            else -> {
                blindTicks = 0
                val target = strategy.decideDegraded(Watt(emsState.gridPower!!), Watt(emsState.batteryPower!!))
                logger.debug(
                    "control(degraded): grid=${emsState.gridPower}W battery=${emsState.batteryPower}W " +
                        "-> target=${target.value}W (>0 = charge, <0 = discharge)"
                )
                world.batteries.values.forEach { battery ->
                    runCatchingLog("set battery") { battery.setChargingPower(target) }
                }
                // Stop/Fixed enforced; default ExcessPower (null) leaves the charger uncommanded.
                override?.let { applyChargerAmps(it) }
            }
        }
```

Note: with the default `chargerControl = ChargingWithExcessPower`, `override` is null, so the existing tier-1/tier-2 tests (which never call `setCharging`) behave exactly as before.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: PASS (new tests + all pre-existing tier1/tier2/blind/MANUAL tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt src/test/kotlin/ems/EnergyManagerTest.kt
git commit -m "feat(ems): apply per-charger control intent each tick, independent of mode"
```

---

## Task 6: `ChargingStateUpdate` message + `/ws` SetCharging handling (server)

**Files:**
- Modify: `src/main/kotlin/Messages.kt`
- Modify: `src/main/kotlin/Sockets.kt`
- Test: `src/test/kotlin/MessagesTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/MessagesTest.kt`:

```kotlin
package io.konektis

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun `ChargingStateUpdate uses the agreed discriminator and encodes the state`() {
        val json = Json.encodeToString(
            Message.ChargingStateUpdate(ChargingState.ChargingWithMaxPower(7400u)) as Message
        )
        assertTrue(json.contains("\"type\":\"ChargingStateUpdate\""), "unexpected discriminator: $json")
        assertTrue(json.contains("\"type\":\"ChargingWithMaxPower\""), "missing nested state: $json")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.MessagesTest"`
Expected: FAIL — `Message.ChargingStateUpdate` unresolved.

- [ ] **Step 3: Add the message type**

In `src/main/kotlin/Messages.kt`, add to the `Message` sealed class (after `ModeUpdate`):

```kotlin
    @Serializable @SerialName("ChargingStateUpdate")
    data class ChargingStateUpdate(val chargingState: ChargingState) : Message()
```

- [ ] **Step 4: Handle SetCharging and push updates in `Sockets.kt`**

In `src/main/kotlin/Sockets.kt`:

After the `modeJob` launch block (around line 65), add a collector that pushes charging-state changes:

```kotlin
            val chargingJob = launch {
                energyManager.chargingStateFlow.collect { state ->
                    if (authenticated) {
                        send(Json.encodeToString(Message.ChargingStateUpdate(state) as Message))
                    }
                }
            }
```

In the `Authenticate` success branch, after sending the current mode, also send the current charging state:

```kotlin
                                    send(Json.encodeToString(Message.ChargingStateUpdate(energyManager.chargingStateFlow.value) as Message))
```

Replace the `else -> { ... }` branch of the `when (val message = ...)` with an explicit `SetCharging` case plus the catch-all:

```kotlin
                            is ClientMessage.SetCharging -> {
                                if (!authenticated) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                } else {
                                    energyManager.setCharging(message.chargingState)
                                    send(Json.encodeToString(Message.ChargingStateUpdate(message.chargingState) as Message))
                                }
                            }
                            else -> {
                                if (!authenticated) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                }
                            }
```

- [ ] **Step 5: Run test + full server build**

Run: `./gradlew test --tests "io.konektis.MessagesTest"`
Expected: PASS.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all server tests green).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/Messages.kt src/main/kotlin/Sockets.kt src/test/kotlin/MessagesTest.kt
git commit -m "feat(ws): handle SetCharging and broadcast ChargingStateUpdate"
```

---

## Task 7: App — `ChargingStateUpdate` + `StatusState.chargerConnection`

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/model/StatusState.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/StatusStateTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`:

```kotlin
    @Test
    fun `ChargingStateUpdate round-trips and uses the agreed discriminator`() {
        val msg = Message.ChargingStateUpdate(ChargingState.ChargingWithExcessPower)
        val json = Json.encodeToString<Message>(msg)
        assertTrue(json.contains("\"type\":\"ChargingStateUpdate\""), "unexpected discriminator: $json")
        assertEquals(msg, Json.decodeFromString<Message>(json))
    }
```

Add to `android/app/src/test/kotlin/io/konektis/ems/StatusStateTest.kt`:

```kotlin
    @Test
    fun `StatusState round-trips chargerConnection`() {
        val state = StatusState(
            devices = emptyList(),
            totalSolarW = null, gridW = null, batteryW = null,
            batteryCharge = null, chargerW = 0, heatpumpW = null,
            chargerConnection = "Connected"
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.WsMessageTest" --tests "io.konektis.ems.StatusStateTest"`
Expected: FAIL — `Message.ChargingStateUpdate` / `chargerConnection` unresolved.

- [ ] **Step 3: Add the app message type**

In `android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt`, add to the `Message` sealed class:

```kotlin
    @Serializable @SerialName("ChargingStateUpdate") data class ChargingStateUpdate(val chargingState: ChargingState) : Message()
```

- [ ] **Step 4: Add the StatusState field**

In `android/app/src/main/kotlin/io/konektis/ems/data/model/StatusState.kt`, add a trailing field to `StatusState`:

```kotlin
    val heatpumpW: Int?,
    val chargerConnection: String? = null
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.WsMessageTest" --tests "io.konektis.ems.StatusStateTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt android/app/src/main/kotlin/io/konektis/ems/data/model/StatusState.kt android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt android/app/src/test/kotlin/io/konektis/ems/StatusStateTest.kt
git commit -m "feat(app): add ChargingStateUpdate message and chargerConnection field"
```

---

## Task 8: App — `ChargerConnection` model + `chargerUiState`

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/model/ChargerConnection.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerUiState.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/ChargerUiStateTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/konektis/ems/ChargerUiStateTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.data.model.ChargerConnection
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargerUiStateTest {
    @Test fun `maps connection to ui state`() {
        assertEquals(ChargerUiState.NO_CAR, chargerUiState(ChargerConnection.NotConnected))
        assertEquals(ChargerUiState.CONNECTED_IDLE, chargerUiState(ChargerConnection.Connected))
        assertEquals(ChargerUiState.CHARGING, chargerUiState(ChargerConnection.Charging))
        assertEquals(ChargerUiState.CONTROLS_FALLBACK, chargerUiState(ChargerConnection.Unknown))
        assertEquals(ChargerUiState.CONTROLS_FALLBACK, chargerUiState(null))
    }

    @Test fun `parses connection names and tolerates junk`() {
        assertEquals(ChargerConnection.Charging, parseChargerConnection("Charging"))
        assertNull(parseChargerConnection("bogus"))
        assertNull(parseChargerConnection(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerUiStateTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Create the model and mapping**

Create `android/app/src/main/kotlin/io/konektis/ems/data/model/ChargerConnection.kt`:

```kotlin
package io.konektis.ems.data.model

/** App-facing car-connection state; mirrors the server's ChargerConnection enum names. */
enum class ChargerConnection { NotConnected, Connected, Charging, Unknown }

fun parseChargerConnection(raw: String?): ChargerConnection? =
    raw?.let { runCatching { ChargerConnection.valueOf(it) }.getOrNull() }
```

Create `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerUiState.kt`:

```kotlin
package io.konektis.ems.ui.charger

import io.konektis.ems.data.model.ChargerConnection

enum class ChargerUiState { NO_CAR, CONNECTED_IDLE, CHARGING, CONTROLS_FALLBACK }

/** Decides which Charger-tab layout to show. Unknown/absent status keeps the controls visible. */
fun chargerUiState(connection: ChargerConnection?): ChargerUiState = when (connection) {
    ChargerConnection.NotConnected -> ChargerUiState.NO_CAR
    ChargerConnection.Connected -> ChargerUiState.CONNECTED_IDLE
    ChargerConnection.Charging -> ChargerUiState.CHARGING
    ChargerConnection.Unknown, null -> ChargerUiState.CONTROLS_FALLBACK
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerUiStateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/model/ChargerConnection.kt android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerUiState.kt android/app/src/test/kotlin/io/konektis/ems/ChargerUiStateTest.kt
git commit -m "feat(app): ChargerConnection model and chargerUiState mapping"
```

---

## Task 9: App — `ControlWsClient` exposes `chargingState`

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`

This task is wiring with no isolated unit test (the client owns a live WebSocket); it is exercised by the build and by the `WsMessageTest` round-trip from Task 7. Verify by compiling.

- [ ] **Step 1: Add the StateFlow and import**

In `ControlWsClient.kt`, add the import:

```kotlin
import io.konektis.ems.data.model.ChargingState
```

Add the flow next to `_mode` (around line 38):

```kotlin
    private val _chargingState = MutableStateFlow<ChargingState?>(null)
    val chargingState: StateFlow<ChargingState?> = _chargingState.asStateFlow()
```

- [ ] **Step 2: Handle the message and reset on disconnect**

In the `when (val msg = ...)` block, add a branch:

```kotlin
                                        is Message.ChargingStateUpdate -> _chargingState.value = msg.chargingState
```

In the `Message.Unauthorized` branch (where `_mode.value = null`), also clear it:

```kotlin
                                            _chargingState.value = null
```

After the `for (frame in incoming)` loop, where `_mode.value = null` is set on disconnect, add:

```kotlin
                            _chargingState.value = null
```

And in the `catch (e: Exception)` block where `_mode.value = null`, add:

```kotlin
                        _chargingState.value = null
```

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt
git commit -m "feat(app): track server charging state in ControlWsClient"
```

---

## Task 10: App — wire `chargingState` through ViewModel + NavHost + DashboardScreen

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/NavHost.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt`

Wiring task; verified by compilation (existing `DashboardViewModelTest` keeps compiling because the new constructor param has a default).

- [ ] **Step 1: Add `chargingState` to the ViewModel**

In `DashboardViewModel.kt`, add the import:

```kotlin
import io.konektis.ems.data.model.ChargingState
```

Add a constructor parameter (with a default, so existing tests compile) after `mode`:

```kotlin
    val mode: StateFlow<ManagerMode?> = MutableStateFlow(null),
    val chargingState: StateFlow<ChargingState?> = MutableStateFlow(null),
```

- [ ] **Step 2: Supply it from `NavHost`**

In `NavHost.kt`, in the `DashboardViewModel(...)` factory, add:

```kotlin
            chargingState = app.component.controlWsClient.chargingState,
```

- [ ] **Step 3: Pass it (and mode) to `ChargerScreen`**

In `DashboardScreen.kt`, collect the charging state near the other `collectAsState()` calls:

```kotlin
    val chargingState by vm.chargingState.collectAsState()
```

Update the `ChargerScreen(...)` call in the `when (selectedTab)` block:

```kotlin
                1 -> ChargerScreen(
                    statusState = status,
                    controlState = controlState,
                    chargingState = chargingState,
                    mode = mode,
                    onSetCharging = vm::setCharging
                )
```

- [ ] **Step 4: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `ChargerScreen` does not yet accept `chargingState`/`mode` (fixed in Task 11). This task's edits are complete; proceed to Task 11 before building.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardViewModel.kt android/app/src/main/kotlin/io/konektis/ems/ui/NavHost.kt android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt
git commit -m "feat(app): plumb server charging state to the Charger tab"
```

---

## Task 11: App — three-state `ChargerScreen`

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`

- [ ] **Step 1: Replace `ChargerScreen.kt`**

Replace the whole file with:

```kotlin
package io.konektis.ems.ui.charger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargerConnection
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

private enum class ChargingMode { SOLAR, MANUAL }

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    chargingState: ChargingState?,
    mode: ManagerMode?,
    onSetCharging: (ChargingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    val chargerW = statusState?.chargerW
    val connection = parseChargerConnection(statusState?.chargerConnection)
    val uiState = chargerUiState(connection)
    val isAuthenticated = controlState is ControlState.Authenticated
    val isCharging = uiState == ChargerUiState.CHARGING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (uiState) {
            ChargerUiState.NO_CAR -> {
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = "No car",
                    valueColor = ems.idle,
                    statusText = "No car connected",
                    online = false,
                )
            }
            else -> {
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = when {
                        isCharging && chargerW != null -> formatWatts(chargerW)
                        isCharging -> "Charging"
                        else -> "Idle"
                    },
                    valueColor = if (isCharging) ems.consumption else ems.idle,
                    statusText = if (isCharging) "Charging" else "Connected — not charging",
                    online = true,
                )

                if (isAuthenticated) {
                    ChargerControls(
                        isCharging = isCharging,
                        chargingState = chargingState,
                        mode = mode,
                        onSetCharging = onSetCharging,
                    )
                } else {
                    Text(
                        "Charger control unavailable — check credentials in Settings.",
                        fontSize = 14.sp,
                        color = ems.idle,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargerControls(
    isCharging: Boolean,
    chargingState: ChargingState?,
    mode: ManagerMode?,
    onSetCharging: (ChargingState) -> Unit,
) {
    val ems = LocalEmsColors.current
    // Initialise from the server's authoritative intent; re-keyed when the server pushes a change.
    var chargingMode by remember(chargingState) {
        mutableStateOf(
            if (chargingState is ChargingState.ChargingWithMaxPower) ChargingMode.MANUAL
            else ChargingMode.SOLAR
        )
    }
    var manualPower by remember(chargingState) {
        mutableIntStateOf((chargingState as? ChargingState.ChargingWithMaxPower)?.maxPower?.toInt() ?: 3680)
    }

    Text("MODE", style = MaterialTheme.typography.labelSmall, color = ems.idle)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = chargingMode == ChargingMode.SOLAR,
            onClick = { chargingMode = ChargingMode.SOLAR },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Solar surplus") }
        SegmentedButton(
            selected = chargingMode == ChargingMode.MANUAL,
            onClick = { chargingMode = ChargingMode.MANUAL },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Fixed power") }
    }

    if (mode == ManagerMode.MANUAL && chargingMode == ChargingMode.SOLAR) {
        Text(
            "EMS is in manual mode — solar-surplus charging is best-effort and may compete with the battery.",
            fontSize = 12.sp,
            color = ems.idle,
        )
    }

    if (chargingMode == ChargingMode.MANUAL) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Max power", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    Text(formatWatts(manualPower), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = manualPower.toFloat(),
                    onValueChange = { manualPower = it.toInt() },
                    valueRange = 1440f..7680f,
                    steps = 25,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("1.4 kW", fontSize = 11.sp, color = ems.idle)
                    Text("7.7 kW", fontSize = 11.sp, color = ems.idle)
                }
            }
        }
    }

    Button(
        onClick = {
            if (isCharging) {
                onSetCharging(ChargingState.NotCharging)
            } else {
                onSetCharging(
                    when (chargingMode) {
                        ChargingMode.SOLAR -> ChargingState.ChargingWithExcessPower
                        ChargingMode.MANUAL -> ChargingState.ChargingWithMaxPower(manualPower.toUInt())
                    }
                )
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isCharging) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            if (isCharging) "STOP CHARGING" else "START CHARGING",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

- [ ] **Step 2: Compile the app**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt
git commit -m "feat(app): three-state Charger tab driven by car-connection status"
```

---

## Task 12: Full build + verification

**Files:** none (verification only).

- [ ] **Step 1: Server build + tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all server tests pass.

- [ ] **Step 2: App unit tests + debug APK**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: all app unit tests pass.

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 3: Manual smoke (optional, requires hardware/charger)**

- Plug/unplug the car → Charger tab switches between "No car connected" and the controls.
- Start (Solar) / Start (Fixed) / Stop → server applies amps; the toggle reflects the echoed
  `ChargingStateUpdate` after reconnect.

- [ ] **Step 4: Commit (if any verification fixups were needed)**

```bash
git add -A
git commit -m "chore: charger-connection-flow build verification"
```

---

## Self-Review Notes

- **Spec coverage:** §1 status → Tasks 1–3, 7–8, 11; §2 control wiring → Tasks 4–6, 9–10; three-state UI → Task 11; MANUAL+ExcessPower best-effort note → Task 11; testing → tests embedded per task.
- **Type consistency:** `ChargerConnection` (enum names identical server/app); `chargerOverrideAmps` used consistently in `WorldSnapshot`, `SurplusPriorityStrategy`, `EnergyManager`; `ChargingStateUpdate` added with matching `@SerialName` on both sides; `chargerUiState`/`ChargerUiState`/`parseChargerConnection` names match across Tasks 8 and 11.
- **Default-compatibility:** new fields (`ChargerState.connection`, `WorldSnapshot.chargerOverrideAmps`, `StatusState.chargerConnection`, `DashboardViewModel.chargingState`) all carry defaults, so existing constructors/tests compile unchanged and the default `ChargingWithExcessPower` preserves current cascade behaviour.
