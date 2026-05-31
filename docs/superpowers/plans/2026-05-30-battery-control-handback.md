# Battery Control Hand-back & Degraded Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the EMS hold Modbus control of the SMA battery only while actively steering it — releasing to the inverter on blind/stale data, MANUAL mode, and graceful shutdown — and keep balancing the grid in a degraded mode when only grid+battery readings are available.

**Architecture:** A `BatteryCommand` sealed type replaces the implicit "always write a target." `SMABattery` owns a write-on-change guard (engaged + lastTarget) and a `releaseToInverter()` path. `EnergyManager` selects one of three tiers (full cascade / degraded grid-balance / blind-release) each tick, tracks a blind counter and AUTO/MANUAL mode, and exposes a mode setter wired through the `/ws` protocol. A graceful JVM shutdown hook writes 803. A standalone Gradle-run tool exercises the inverter watchdog on real hardware.

**Tech Stack:** Kotlin, Ktor (`/ws` WebSocket), kotlin-inject DI, digitalpetri Modbus, kotlinx.serialization; tests use `kotlin.test` (JUnit) + MockK + `kotlinx-coroutines-test`.

---

## Conventions

- All commands run from the repo root: `/home/koen/Code/ems-server`.
- Build/compile: `./gradlew compileKotlin`
- Run all tests: `./gradlew test`
- Run one test class: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
- Power sign convention (whole project): **negative = producing/exporting, positive = consuming/importing**. Battery `power`/target: **positive = charging, negative = discharging**.
- Existing tests use MockK (`mockk`, `coEvery`, `just runs`) and `runTest`. Follow that style.
- Commit messages end with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer (omitted from the snippets below for brevity — add it).

## File Structure

**Create:**
- `src/main/kotlin/ems/BatteryCommand.kt` — `sealed interface BatteryCommand { SetPower, ReleaseToInverter }`
- `src/main/kotlin/tools/BatteryWatchdogTest.kt` — standalone `main()` watchdog probe
- `src/test/kotlin/ems/EnergyManagerTest.kt` — tier selection, blind counter, MANUAL transition
- `src/test/kotlin/devices/battery/SMABatteryGuardTest.kt` — write-on-change guard state machine

**Modify:**
- `src/main/kotlin/devices/battery/Battery.kt` — add `releaseToInverter()`
- `src/main/kotlin/devices/battery/SMABattery.kt` — injectable Modbus client, guard, release, check write results
- `src/main/kotlin/ems/Strategy.kt` — `ControlDecisions.batteryCommand`; add `decideDegraded`
- `src/main/kotlin/ems/SurplusPriorityStrategy.kt` — produce `BatteryCommand.SetPower`; implement `decideDegraded`
- `src/main/kotlin/ems/EnergyManager.kt` — tiers, blind counter, mode setter + transition handling, dispatch
- `src/main/kotlin/Messages.kt` — `ManagerMode` enum, `ClientMessage.SetMode`, mode in outbound state
- `src/main/kotlin/Sockets.kt` — handle `SetMode`; receive `EnergyManager`
- `src/main/kotlin/Application.kt` — thread `EnergyManager` into `module`; shutdown hook
- `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt` — update `batteryTargetPower` → `batteryCommand`; add degraded tests
- `build.gradle.kts` — `JavaExec` task for the watchdog tool

---

## Task 1: `BatteryCommand` type

**Files:**
- Create: `src/main/kotlin/ems/BatteryCommand.kt`

- [ ] **Step 1: Create the type**

Create `src/main/kotlin/ems/BatteryCommand.kt`:

```kotlin
package io.konektis.ems

import io.konektis.devices.Watt

/** What the EMS wants the battery to do this tick. */
sealed interface BatteryCommand {
    /** Hold Modbus control (802) and target [power] W (positive=charge, negative=discharge). */
    data class SetPower(val power: Watt) : BatteryCommand

    /** Hand control back to the inverter (803). */
    data object ReleaseToInverter : BatteryCommand
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ems/BatteryCommand.kt
git commit -m "feat(ems): add BatteryCommand sealed type"
```

---

## Task 2: `Battery.releaseToInverter()` + injectable Modbus client in `SMABattery`

The current `SMABattery` hard-creates `ModbusTCPClient(host)` in a field initializer, so the guard can't be unit-tested. Extract a tiny interface and inject it. This task only refactors + adds the interface method (guard logic comes in Task 3).

**Files:**
- Modify: `src/main/kotlin/devices/battery/Battery.kt`
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt`

- [ ] **Step 1: Add `releaseToInverter` to the interface**

Replace the body of `src/main/kotlin/devices/battery/Battery.kt`:

```kotlin
package io.konektis.devices.battery

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

data class BatteryState(
    val charge: UShort,
    val power: Watt
)

interface Battery {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<BatteryState>?
    suspend fun setChargingPower(power: Watt)
    suspend fun releaseToInverter()
}
```

- [ ] **Step 2: Make `SMABattery`'s Modbus access injectable**

In `src/main/kotlin/devices/battery/SMABattery.kt`, define a minimal seam over the two raw Modbus operations the battery needs, defaulting to the real client. Replace the class declaration and the `client` field (top of the class):

```kotlin
/** Minimal seam over the raw Modbus reads/writes SMABattery needs, so the guard is testable. */
interface BatteryModbus {
    suspend fun readInput(register: Int, count: Int): com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
    suspend fun writeRegisters(register: Int, count: Int, values: ByteArray): Unit
}

private class RealBatteryModbus(host: String) : BatteryModbus {
    private val client = io.konektis.ModbusTCPClient(host)
    override suspend fun readInput(register: Int, count: Int) =
        client.withClient { it.readInputRegisters(3, com.digitalpetri.modbus.pdu.ReadInputRegistersRequest(register, count)) }
    override suspend fun writeRegisters(register: Int, count: Int, values: ByteArray) {
        client.withClient { it.writeMultipleRegisters(3, com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest(register, count, values)) }
    }
}

class SMABattery(private val modbus: BatteryModbus) : Klogging, Battery {
    constructor(host: String) : this(RealBatteryModbus(host))
```

Then update the existing read helpers to call `modbus.readInput(...)` instead of `client.withClient { ... readInputRegisters ... }`. Concretely, replace each of `getCharge`, `getCurrentCharge`, `getCurrentDischarge`, `getCapacity` bodies to use the seam, e.g.:

```kotlin
    private suspend fun getCurrentCharge(): UInt {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE, 2)
        return Endian.Big.intFrom(result.registers, 0).toUInt()
    }
```

(Apply the same pattern to `getCharge` [count 4], `getCurrentDischarge` [count 2], `getCapacity` [count 2]. Remove the now-unused `import ...ReadInputRegistersRequest` / `WriteMultipleRegistersRequest` only if no longer referenced — they are still referenced inside `RealBatteryModbus`, so keep them or fully-qualify as shown.)

- [ ] **Step 3: Port `setChargingPower` and add `releaseToInverter` over the seam (no guard yet)**

Replace `setChargingPower` and add `releaseToInverter` in `SMABattery`:

```kotlin
    override suspend fun setChargingPower(power: Watt) {
        mutex.withLock {
            val enable = ByteArray(4).also { Endian.Big.pack(802, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, enable)
            val target = ByteArray(4).also { Endian.Big.pack(power.value, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_POWER, 2, target)
        }
    }

    override suspend fun releaseToInverter() {
        mutex.withLock {
            val disable = ByteArray(4).also { Endian.Big.pack(803, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, disable)
        }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/devices/battery/Battery.kt src/main/kotlin/devices/battery/SMABattery.kt
git commit -m "refactor(battery): inject Modbus seam; add releaseToInverter (803)"
```

---

## Task 3: Write-on-change guard in `SMABattery` (TDD)

**Files:**
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt`
- Create: `src/test/kotlin/devices/battery/SMABatteryGuardTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/devices/battery/SMABatteryGuardTest.kt`:

```kotlin
package io.konektis.devices.battery

import io.konektis.devices.Watt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeBatteryModbus : BatteryModbus {
    // record (register, first-4-byte int value) for each write
    val writes = mutableListOf<Pair<Int, Int>>()
    var failNextWrite = false

    override suspend fun readInput(register: Int, count: Int) =
        throw UnsupportedOperationException("not needed for guard tests")

    override suspend fun writeRegisters(register: Int, count: Int, values: ByteArray) {
        if (failNextWrite) { failNextWrite = false; throw RuntimeException("modbus write failed") }
        val v = ((values[0].toInt() and 0xFF) shl 24) or
                ((values[1].toInt() and 0xFF) shl 16) or
                ((values[2].toInt() and 0xFF) shl 8) or
                (values[3].toInt() and 0xFF)
        writes.add(register to v)
    }
}

class SMABatteryGuardTest {
    private val CONTROL = 40151
    private val POWER = 40149

    @Test fun `first setChargingPower writes 802 then target`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        assertEquals(listOf(CONTROL to 802, POWER to 1000), m.writes)
    }

    @Test fun `within-epsilon repeat writes nothing`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        m.writes.clear()
        b.setChargingPower(Watt(1010)) // delta 10 <= 25
        assertEquals(emptyList(), m.writes)
    }

    @Test fun `beyond-epsilon change writes target only`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(1000))
        m.writes.clear()
        b.setChargingPower(Watt(1100)) // delta 100 > 25
        assertEquals(listOf(POWER to 1100), m.writes)
    }

    @Test fun `release writes 803 only when engaged`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.releaseToInverter()                 // not engaged → no-op
        assertEquals(emptyList(), m.writes)
        b.setChargingPower(Watt(500))
        m.writes.clear()
        b.releaseToInverter()                 // engaged → 803
        assertEquals(listOf(CONTROL to 803), m.writes)
        m.writes.clear()
        b.releaseToInverter()                 // already released → no-op
        assertEquals(emptyList(), m.writes)
    }

    @Test fun `re-engages with 802 after a release`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(500))
        b.releaseToInverter()
        m.writes.clear()
        b.setChargingPower(Watt(500))         // must re-arm 802 even if target unchanged
        assertEquals(listOf(CONTROL to 802, POWER to 500), m.writes)
    }

    @Test fun `failed target write does not advance guard state`() = runTest {
        val m = FakeBatteryModbus()
        val b = SMABattery(m)
        b.setChargingPower(Watt(500))         // engaged, last=500
        m.writes.clear()
        m.failNextWrite = true
        runCatching { b.setChargingPower(Watt(2000)) } // throws inside
        // next call must retry the target (lastTarget not advanced to 2000)
        b.setChargingPower(Watt(2000))
        assertEquals(listOf(POWER to 2000), m.writes)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.konektis.devices.battery.SMABatteryGuardTest"`
Expected: FAIL — current `setChargingPower` always writes 802+target; epsilon/engaged logic not present.

- [ ] **Step 3: Implement the guard**

In `SMABattery`, add guard state fields near the top of the class:

```kotlin
    private var engaged = false
    private var lastTarget: Int? = null
    private val POWER_EPSILON_W = 25
```

Replace `setChargingPower` and `releaseToInverter` with guarded versions:

```kotlin
    override suspend fun setChargingPower(power: Watt) {
        mutex.withLock {
            if (!engaged) {
                val enable = ByteArray(4).also { Endian.Big.pack(802, it, 0) }
                modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, enable)
                engaged = true
            } else {
                val last = lastTarget
                if (last != null && kotlin.math.abs(power.value - last) <= POWER_EPSILON_W) return
            }
            val target = ByteArray(4).also { Endian.Big.pack(power.value, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_POWER, 2, target)
            lastTarget = power.value
        }
    }

    override suspend fun releaseToInverter() {
        mutex.withLock {
            if (!engaged) return
            val disable = ByteArray(4).also { Endian.Big.pack(803, it, 0) }
            modbus.writeRegisters(MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL, 2, disable)
            engaged = false
            lastTarget = null
        }
    }
```

Note: guard state advances only after `writeRegisters` returns. If a write throws, `engaged`/`lastTarget` are not updated past the failed step (the 802 case sets `engaged=true` before the target write — acceptable: control is enabled, the target simply retries next tick). The `failed target write` test exercises the engaged path where `lastTarget` must not advance.

`writeRegisters` failure propagates as an exception (the seam throws; `RealBatteryModbus` surfaces Modbus errors via `ModbusTCPClient.withClient`, which already rethrows). This satisfies the spec's "check write results" intent — a failed write throws rather than being silently ignored.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "io.konektis.devices.battery.SMABatteryGuardTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/devices/battery/SMABattery.kt src/test/kotlin/devices/battery/SMABatteryGuardTest.kt
git commit -m "feat(battery): write-on-change guard with 25W epsilon; tests"
```

---

## Task 4: Strategy — `batteryCommand` + `decideDegraded` (TDD)

**Files:**
- Modify: `src/main/kotlin/ems/Strategy.kt`
- Modify: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`
- Modify: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`

- [ ] **Step 1: Update the failing tests**

In `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`, replace the two battery assertions to use the new command type, and add degraded-mode tests. Replace the `battery absorbs remaining surplus` and `deficit discharges battery` tests with:

```kotlin
    @Test
    fun `battery absorbs remaining surplus`() {
        val decisions = strategy.decide(snapshot(grid = -3000))
        assertEquals(BatteryCommand.SetPower(Watt(3000)), decisions.batteryCommand)
    }

    @Test
    fun `deficit discharges battery`() {
        val decisions = strategy.decide(snapshot(grid = 1500))
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }

    @Test
    fun `degraded importing grid discharges more`() {
        // currentBattery=200 (charging), grid=+600 importing → target = 200 - 600 = -400
        assertEquals(Watt(-400), strategy.decideDegraded(Watt(600), Watt(200)))
    }

    @Test
    fun `degraded exporting grid charges more`() {
        // currentBattery=200, grid=-500 exporting → target = 200 - (-500) = 700
        assertEquals(Watt(700), strategy.decideDegraded(Watt(-500), Watt(200)))
    }

    @Test
    fun `degraded balanced grid holds current battery power`() {
        assertEquals(Watt(300), strategy.decideDegraded(Watt(0), Watt(300)))
    }
```

Add the import at the top of the test file:

```kotlin
import io.konektis.ems.BatteryCommand
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: FAIL — `batteryCommand` and `decideDegraded` don't exist yet.

- [ ] **Step 3: Update `Strategy.kt`**

Replace `src/main/kotlin/ems/Strategy.kt`:

```kotlin
package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode

data class WorldSnapshot(
    val gridPower: Watt,          // signed: negative = exporting, positive = importing
    val solarPower: Watt,
    val batteryCharge: UShort,    // state of charge, percent 0-100
    val batteryPower: Watt,       // net power: positive = charging, negative = discharging
    val chargerPower: Watt,
    val heatpumpPower: Watt,
    val chargerMinAmps: Int,
    val chargerMaxAmps: Int
)

data class ControlDecisions(
    val chargerMaxAmps: Int?,             // null = no change
    val batteryCommand: BatteryCommand?,  // null = no change
    val heatpumpConsumeMode: ConsumeMode? // null = no change
)

interface Strategy {
    /** Full-data decision (tier 1). */
    fun decide(snapshot: WorldSnapshot): ControlDecisions

    /** Degraded decision (tier 2): battery target to drive grid → 0 using only grid + battery. */
    fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt
}
```

- [ ] **Step 4: Update `SurplusPriorityStrategy.kt`**

In `src/main/kotlin/ems/SurplusPriorityStrategy.kt`, change the returned battery field and add the degraded function. Replace the `return ControlDecisions(...)` block and append the new method:

```kotlin
        return ControlDecisions(
            chargerMaxAmps = chargerAmps,
            batteryCommand = BatteryCommand.SetPower(batteryTarget),
            heatpumpConsumeMode = heatpumpMode
        )
    }

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        Watt(batteryPower.value - gridPower.value)
}
```

(Keep the existing `available` / `batteryTarget` computation above unchanged.)

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ems/Strategy.kt src/main/kotlin/ems/SurplusPriorityStrategy.kt src/test/kotlin/ems/SurplusPriorityStrategyTest.kt
git commit -m "feat(ems): batteryCommand decisions + degraded grid-balance strategy"
```

---

## Task 5: `ManagerMode` + `SetMode` protocol

**Files:**
- Modify: `src/main/kotlin/Messages.kt`

- [ ] **Step 1: Add the wire enum and client message**

In `src/main/kotlin/Messages.kt`, add after the `Devices` enum:

```kotlin
@Serializable
enum class ManagerMode { AUTO, MANUAL }
```

Add a new `ClientMessage` variant inside the existing `sealed class ClientMessage`:

```kotlin
    @Serializable data class SetMode(val mode: ManagerMode) : ClientMessage()
```

Add a server→client message inside `sealed class Message` so clients can see the active mode:

```kotlin
    @Serializable data class ModeUpdate(val mode: ManagerMode) : Message()
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/Messages.kt
git commit -m "feat(protocol): add ManagerMode, SetMode, ModeUpdate messages"
```

---

## Task 6: `EnergyManager` — tiers, blind counter, mode, dispatch (TDD)

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Create: `src/test/kotlin/ems/EnergyManagerTest.kt`

The control loop currently lives in a `while(true)` with `delay`. To make decision-making testable without time, extract a pure-ish `suspend fun tick()` that runs exactly one cycle, and have `run()` call it in the loop. Tests call `tick()` directly.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/ems/EnergyManagerTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.config.ChargingCurrent
import io.konektis.config.Config
import io.konektis.config.Charger as ChargerConfig
import io.konektis.config.ChargerType
import io.konektis.config.Devices as DevicesConfig
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.charger.Charger
import io.konektis.devices.charger.ChargerState
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.clearMocks
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.TimeSource

private fun grid(power: Int?): Grid = mockk<Grid>().also {
    coEvery { it.update() } just runs
    coEvery { it.getState() } returns power?.let {
        p -> DeviceUpdate(TimeSource.Monotonic.markNow(), GridState(Watt(p), Volt(230u)))
    }
}
private fun battery(power: Int?): Battery = mockk<Battery>(relaxed = true).also {
    coEvery { it.getState() } returns power?.let {
        p -> DeviceUpdate(TimeSource.Monotonic.markNow(), BatteryState(50u, Watt(p)))
    }
}
private fun charger(power: Int?): Charger = mockk<Charger>(relaxed = true).also {
    coEvery { it.getState() } returns power?.let {
        p -> DeviceUpdate(TimeSource.Monotonic.markNow(), ChargerState(Watt(p)))
    }
}

private fun config() = Config(
    grid = mockk(relaxed = true),
    devices = DevicesConfig(
        charger = listOf(ChargerConfig(ChargerType.WebastoUnite, "c", "h", ChargingCurrent(6.0, 32.0)))
    ),
    ocpp = mockk(relaxed = true),   // required: Config has no default for ocpp
    websocket = mockk(relaxed = true),
    refreshThreads = 1
)

class EnergyManagerTest {

    private fun manager(world: World) = EnergyManager(world, config(), SurplusPriorityStrategy())

    @Test fun `tier1 full data sets battery via cascade`() = runTest {
        val bat = battery(0)
        val world = World(grid(-3000), mapOf("c" to charger(0)), emptyMap(),
            mapOf<String, SmartConsumer>("h" to mockk(relaxed = true)), mapOf("b" to bat))
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(3000)) }
    }

    @Test fun `tier2 missing charger still balances battery on grid`() = runTest {
        val bat = battery(200)
        // charger present in map but returns null state → degraded
        val world = World(grid(600), mapOf("c" to charger(null)), emptyMap(),
            emptyMap(), mapOf("b" to bat))
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(-400)) } // 200 - 600
    }

    @Test fun `tier2 does not command charger`() = runTest {
        val ch = charger(null)
        val bat = battery(0)
        val world = World(grid(0), mapOf("c" to ch), emptyMap(), emptyMap(), mapOf("b" to bat))
        manager(world).tick()
        coVerify(exactly = 0) { ch.setMaxChargerPower(any()) }
    }

    @Test fun `blind releases after 6 ticks then once`() = runTest {
        val bat = battery(0)
        val world = World(grid(null), emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        repeat(5) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() }
        m.tick() // 6th
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick() // 7th — not re-fired
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }

    @Test fun `good tick resets blind counter`() = runTest {
        val bat = battery(0)
        // grid flips null/present via sequential returns
        val g = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returnsMany listOf(
                null, null, null,
                DeviceUpdate(TimeSource.Monotonic.markNow(), GridState(Watt(0), Volt(230u))),
                null, null, null
            )
        }
        val world = World(g, emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        repeat(7) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() } // never 6 consecutive blind
    }

    @Test fun `switching to MANUAL releases battery once`() = runTest {
        val bat = battery(0)
        val world = World(grid(-1000), mapOf("c" to charger(0)), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        m.tick()                       // AUTO, engages
        clearMocks(bat, answers = false, recordedCalls = true)
        m.setMode(Mode.MANUAL)
        m.tick()                       // transition handled
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick()                       // stays manual, no further release
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }
}
```

(If the real `Config` constructor signature differs, adapt the `config()` helper to match — the key inputs the manager reads are `config.devices.charger.first().chargingCurrent.min/max`.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: FAIL — `tick()`, `setMode`, tier logic, blind counter, and MANUAL handling don't exist yet.

- [ ] **Step 3: Rewrite `EnergyManager.kt`**

Replace `src/main/kotlin/ems/EnergyManager.kt`:

```kotlin
package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.ManagerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration

enum class Mode { AUTO, MANUAL }

class EnergyManager(
    private val world: World,
    private val config: Config,
    private val strategy: Strategy
) : Klogging {

    @Volatile var mode = Mode.AUTO
        private set
    private var previousMode = Mode.AUTO
    private var blindTicks = 0

    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))
    val modeFlow = MutableStateFlow(ManagerMode.AUTO)

    fun setMode(newMode: Mode) {
        mode = newMode
        modeFlow.value = if (newMode == Mode.AUTO) ManagerMode.AUTO else ManagerMode.MANUAL
    }

    suspend fun run() {
        while (true) {
            tick()
            delay(Duration.ofSeconds(5))
        }
    }

    /** One control cycle. Extracted for testability. */
    suspend fun tick() {
        val emsState = buildEMSState()
        emsStateFlow.value = emsState

        // Handle AUTO->MANUAL transition once.
        if (mode == Mode.MANUAL && previousMode == Mode.AUTO) {
            releaseAll()
        }
        previousMode = mode

        if (mode != Mode.AUTO) return

        when {
            // Tier 3: blind (grid or battery missing)
            emsState.gridPower == null || emsState.batteryPower == null -> {
                blindTicks++
                if (blindTicks == BLIND_RELEASE_TICKS) {
                    world.batteries.values.forEach { runCatchingLog("release battery") { it.releaseToInverter() } }
                }
            }
            // Tier 1: full data
            emsState.chargerPower != null && emsState.heatpumpPower != null -> {
                blindTicks = 0
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot != null) applyDecisions(strategy.decide(snapshot))
            }
            // Tier 2: degraded (grid + battery present; charger/heatpump missing)
            else -> {
                blindTicks = 0
                val target = strategy.decideDegraded(Watt(emsState.gridPower), Watt(emsState.batteryPower))
                world.batteries.values.forEach { runCatchingLog("set battery") { it.setChargingPower(target) } }
            }
        }
    }

    private suspend fun releaseAll() {
        world.batteries.values.forEach { runCatchingLog("release battery") { it.releaseToInverter() } }
        world.smartConsumers.values.forEach { runCatchingLog("heatpump normal") { it.setConsumeMode(ConsumeMode.Unrestricted) } }
        // TODO: charger release is best-effort. A full revert needs stopping the Webasto
        // keepalive (register 6000); not implemented. We simply stop sending setpoints.
    }

    suspend fun buildEMSState(): EMSState {
        val gridState = world.grid.getState()?.update
        val solarStates = world.solar.values.mapNotNull { it.getState()?.update?.power?.value }
        val solarPower = if (solarStates.isEmpty()) null else solarStates.sum()
        val batteryState = world.batteries.values.firstOrNull()?.getState()?.update
        val chargerState = world.chargers.values.firstOrNull()?.getState()?.update
        val heatpumpState = world.smartConsumers.values.firstOrNull()?.getState()?.update
        return EMSState(
            gridPower = gridState?.power?.value,
            gridVoltage = gridState?.voltage?.value?.toInt(),
            chargerPower = chargerState?.currentPower?.value,
            heatpumpPower = heatpumpState?.power?.value,
            solarPower = solarPower,
            batteryPower = batteryState?.power?.value,
            batteryCharge = batteryState?.charge?.toInt()
        )
    }

    private fun buildWorldSnapshot(emsState: EMSState): WorldSnapshot? {
        val chargerConfig = config.devices.charger.firstOrNull() ?: return null
        if (emsState.gridPower == null || emsState.chargerPower == null) return null
        return WorldSnapshot(
            gridPower = Watt(emsState.gridPower),
            solarPower = Watt(emsState.solarPower ?: 0),
            batteryCharge = (emsState.batteryCharge ?: 0).toUShort(),
            batteryPower = Watt(emsState.batteryPower ?: 0),
            chargerPower = Watt(emsState.chargerPower),
            heatpumpPower = Watt(emsState.heatpumpPower ?: 0),
            chargerMinAmps = chargerConfig.chargingCurrent.min.toInt(),
            chargerMaxAmps = chargerConfig.chargingCurrent.max.toInt()
        )
    }

    private suspend fun applyDecisions(decisions: ControlDecisions) {
        decisions.chargerMaxAmps?.let { amps ->
            world.chargers.values.forEach { runCatchingLog("set charger") { it.setMaxChargerPower(Watt(amps * 230)) } }
        }
        decisions.batteryCommand?.let { cmd ->
            world.batteries.values.forEach {
                runCatchingLog("set battery") {
                    when (cmd) {
                        is BatteryCommand.SetPower -> it.setChargingPower(cmd.power)
                        BatteryCommand.ReleaseToInverter -> it.releaseToInverter()
                    }
                }
            }
        }
        decisions.heatpumpConsumeMode?.let { consumeMode ->
            world.smartConsumers.values.forEach { runCatchingLog("set heat pump") { it.setConsumeMode(consumeMode) } }
        }
    }

    private suspend fun runCatchingLog(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { logger.error("Failed to $what", e) }
    }

    companion object {
        const val BLIND_RELEASE_TICKS = 6  // ~30s at 5s cadence
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt src/test/kotlin/ems/EnergyManagerTest.kt
git commit -m "feat(ems): three-tier control, blind-release counter, MANUAL transition"
```

---

## Task 7: Wire `SetMode` through `/ws` and `Application`

**Files:**
- Modify: `src/main/kotlin/Sockets.kt`
- Modify: `src/main/kotlin/Application.kt`

- [ ] **Step 1: Thread `EnergyManager` into the socket module**

In `src/main/kotlin/Application.kt`, change the `module` signature and the call site.

Call site (inside `embeddedServer(... ) { module(...) }`):

```kotlin
                    module(energyManager, config.websocket, dataCollector.statusStateFlow)
```

Signature + body — replace the `fun Application.module(...)` declaration line:

```kotlin
fun Application.module(energyManager: EnergyManager, wsConfig: WebSocketConfig, statusFlow: Flow<StatusState?>) {
    configureSecurity()
    configureAdministration()
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
```

(Remove the now-unused `EMSState` import only if it is no longer referenced elsewhere in the file; it is used by `configureSockets`'s signature change below, imported there.)

- [ ] **Step 2: Handle `SetMode` in `Sockets.kt`**

In `src/main/kotlin/Sockets.kt`, change `configureSockets` to take the manager and handle the message. Replace the signature and the relevant parts:

```kotlin
import io.konektis.ems.EnergyManager
import io.konektis.ems.Mode

fun Application.configureSockets(energyManager: EnergyManager, wsConfig: WebSocketConfig) {
    val emsStateFlow = energyManager.emsStateFlow
```

Add a `SetMode` branch to the authenticated `when (val message = ...)`:

```kotlin
                            is ClientMessage.SetMode -> {
                                if (!authenticated) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                } else {
                                    energyManager.setMode(
                                        if (message.mode == ManagerMode.AUTO) Mode.AUTO else Mode.MANUAL
                                    )
                                    send(Json.encodeToString(Message.ModeUpdate(message.mode) as Message))
                                }
                            }
```

(The `Authenticate` branch stays; the `else` branch stays for unknown/unauth messages.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/Sockets.kt src/main/kotlin/Application.kt
git commit -m "feat(ws): handle SetMode; thread EnergyManager into socket module"
```

---

## Task 8: Graceful shutdown hook (803 on stop)

**Files:**
- Modify: `src/main/kotlin/Application.kt`

- [ ] **Step 1: Register the hook**

In `src/main/kotlin/Application.kt`, add imports:

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
```

Right after `val energyManager = component.energyManager` (before the `coroutineScope { ... }`), add:

```kotlin
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                withTimeoutOrNull(3_000) {
                    component.world.batteries.values.forEach {
                        runCatching { it.releaseToInverter() }
                    }
                }
            }
        })
```

(`component.world` is already exposed on `AppComponent`.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/Application.kt
git commit -m "feat(app): release battery to inverter (803) on graceful shutdown"
```

---

## Task 9: Standalone watchdog test tool

**Files:**
- Create: `src/main/kotlin/tools/BatteryWatchdogTest.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Create the tool**

Create `src/main/kotlin/tools/BatteryWatchdogTest.kt`:

```kotlin
package io.konektis.tools

import io.konektis.config.loadConfig
import io.konektis.devices.Watt
import io.konektis.devices.battery.SMABattery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Standalone probe for the SMA inverter's Modbus-control watchdog.
 *
 * Procedure:
 *   1. Run: ./gradlew batteryWatchdogTest
 *   2. It arms Modbus control (802) + a 500W charge target, then prints SoC + net power
 *      every ~3s, forever.
 *   3. Ctrl-C it WITHOUT letting it send 803, then watch the inverter: does it revert to
 *      its own logic, and after how long?
 *
 * TODO: This MUST be run against real hardware to confirm/quantify the watchdog timeout.
 *       Prior manual observation suggests >= 15 minutes (too slow to be a safety net, which
 *       is why graceful 803-on-shutdown is required).
 */
fun main() = runBlocking {
    val config = loadConfig("/config.yaml")
    val batteryConfig = config.devices.battery.firstOrNull()
        ?: error("No battery configured in config.yaml")
    val battery = SMABattery(batteryConfig.host)

    println("Arming Modbus control + 500W charge target on ${batteryConfig.host} ...")
    battery.setChargingPower(Watt(500))
    println("Armed. Polling every 3s. Ctrl-C WITHOUT releasing to observe the watchdog.")

    while (true) {
        battery.update()
        val state = battery.getState()?.update
        println("SoC=${state?.charge}%  netPower=${state?.power?.value}W")
        delay(3_000)
    }
}
```

- [ ] **Step 2: Add the Gradle run task**

In `build.gradle.kts`, inside the existing `tasks { ... }` block (next to `shadowJar`), add:

```kotlin
    register<JavaExec>("batteryWatchdogTest") {
        group = "tools"
        description = "Arm SMA battery Modbus control and poll, to probe the inverter watchdog."
        mainClass.set("io.konektis.tools.BatteryWatchdogTestKt")
        classpath = sourceSets["main"].runtimeClasspath
    }
```

- [ ] **Step 3: Verify it compiles and the task is registered**

Run: `./gradlew compileKotlin tasks --group tools`
Expected: BUILD SUCCESSFUL and `batteryWatchdogTest` listed. (Do **not** run the task — it needs real hardware.)

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/tools/BatteryWatchdogTest.kt build.gradle.kts
git commit -m "feat(tools): standalone SMA battery watchdog probe (manual hardware test)"
```

---

## Task 10: Full build, test, and docs touch-up

**Files:**
- Modify: `CLAUDE.md` (battery quirk note)

- [ ] **Step 1: Update the battery quirk note**

In `CLAUDE.md`, under "Key Quirks", replace the `SMABattery` bullet with:

```markdown
- **SMABattery**: write 802 to register 40151 to enable Modbus power control *before* writing
  target power to register 40149. Write 803 to release control back to the inverter. The EMS
  holds control only while steering and releases (803) on MANUAL mode, on grid/battery data
  going blind for ~30s, and on graceful shutdown. The inverter's own watchdog is too slow
  (≥15 min) to rely on for crash recovery.
```

- [ ] **Step 2: Full compile + test**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all existing tests plus the new `SMABatteryGuardTest`, `EnergyManagerTest`, and updated `SurplusPriorityStrategyTest` pass.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document battery control hand-back behavior"
```

---

## Self-Review Notes

- **Spec coverage:** command model + guard (Tasks 1–3) ✓; fix ignored write result — surfaced as thrown exception through the seam (Task 3) ✓; three-tier control + degraded law + blind counter (Tasks 4, 6) ✓; MANUAL = release all, transition-once (Task 6) ✓; `SetMode` over `/ws` + mode reported back via `ModeUpdate`/`modeFlow` (Tasks 5, 7) ✓; graceful shutdown 803 (Task 8) ✓; watchdog tool with run-on-hardware TODO (Task 9) ✓; Android follow-up remains a documented out-of-scope note (spec) ✓.
- **Charger release best-effort:** Task 6 `releaseAll` documents the keepalive-stop TODO; no charger command is sent in MANUAL/degraded. ✓
- **Type consistency:** `BatteryCommand.SetPower`/`ReleaseToInverter`, `ControlDecisions.batteryCommand`, `Strategy.decide`/`decideDegraded`, `Battery.releaseToInverter`, `EnergyManager.tick`/`setMode`/`Mode`, `ManagerMode`, `Message.ModeUpdate`, `ClientMessage.SetMode`, `BLIND_RELEASE_TICKS` are used consistently across tasks.
- **Config shape verified:** the `EnergyManagerTest` `config()` helper matches the real `Config` constructor read from `config/Config.kt` — `ocpp: OcppConfig` is required (no default) and is supplied via `mockk`; `ChargingCurrent` takes `Double` (`6.0, 32.0`), not UInt. Named arguments make field order irrelevant. The `Sockets.kt`/`Application.kt` edits still assume the current `module` shape read during planning; if that signature differs at implementation time, adapt it (the data the manager actually reads is the charger min/max amps).
- **Modeupdate on connect:** clients learn the mode when they send `SetMode` or via `modeFlow`; wiring the initial push into the existing `emsStateFlow.collect` block is optional polish, not required by the spec, so it is left out (YAGNI).
