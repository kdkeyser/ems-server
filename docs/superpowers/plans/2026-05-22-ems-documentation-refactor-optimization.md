# EMS Documentation, Refactor & Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document the current EMS codebase, refactor EnergyManager to read from the shared `World` device state, then implement a real-time surplus-priority optimization strategy.

**Architecture:** `DataCollector` polls all devices every 5s into shared `World` state. `EnergyManager` reads `World` on each tick to build `EMSState`, applies `Strategy.decide()` to get `ControlDecisions`, then writes those decisions back to devices. `EMSState` is emitted on a `StateFlow` consumed by the WebSocket endpoint.

**Tech Stack:** Kotlin, Ktor (Netty), Modbus TCP (digitalpetri), Coroutines + Dispatchers.IO, kotlin-inject (DI), Hoplite (YAML config), JUnit4 + Kotlin Test

---

## File Map

### Phase 1 — Documentation (new files, no code changes)
- Create: `CLAUDE.md` — AI navigation guide (packages, protocols, quirks, known issues)
- Create: `README.md` — developer onboarding (hardware, build, run)
- Create: `docs/architecture.md` — target architecture + gap analysis
- Modify: `src/main/kotlin/devices/solar/SMASolar.kt` — inline comments
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt` — inline comments
- Modify: `src/main/kotlin/devices/charger/Webasto.kt` — inline comments
- Modify: `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt` — inline comments
- Modify: `src/main/kotlin/Messages.kt` — sign convention comment

### Phase 2 — Refactor
- Modify: `src/main/kotlin/ModbusTCPClient.kt` — suspend `withClient`, fix lock bug
- Modify: `src/main/kotlin/devices/solar/SMASolar.kt` — `getSolarState()` → suspend
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt` — all helpers → suspend, complete `getInternalState()`, fix control register (801→802)
- Modify: `src/main/kotlin/devices/charger/Webasto.kt` — `getCurrentPowerUsage()` → suspend, replace `thread { runBlocking }` with coroutine
- Modify: `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt` — `getState()` → suspend, implement `setConsumeMode()`
- Modify: `src/main/kotlin/config/Config.kt` — add `WebSocketConfig`
- Modify: `src/main/resources/config.yaml` — add `websocket` section
- Modify: `src/main/kotlin/Sockets.kt` — read credentials from config
- Modify: `src/main/kotlin/ems/EnergyManager.kt` — inject `World`+`Config`, `buildEMSState()`, remove old device instances
- Modify: `src/main/kotlin/di/AppModule.kt` — update `provideEnergyManager`
- Modify: `src/main/kotlin/Application.kt` — pass `wsConfig` to module, re-enable `energyManager.run()`

### Phase 3 — Optimization
- Create: `src/main/kotlin/ems/Strategy.kt` — `WorldSnapshot`, `ControlDecisions`, `Strategy` interface
- Create: `src/main/kotlin/ems/SurplusPriorityStrategy.kt` — default strategy
- Create: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt` — unit tests
- Modify: `src/main/kotlin/ems/EnergyManager.kt` — inject `Strategy`, apply `ControlDecisions`
- Modify: `src/main/kotlin/di/AppModule.kt` — provide `Strategy`

---

## Phase 1: Documentation

### Task 1: Write CLAUDE.md

**Files:**
- Create: `CLAUDE.md`

- [ ] **Step 1: Create CLAUDE.md**

```markdown
# CLAUDE.md

Home Energy Management System server — Kotlin/Ktor service that reads power data from a
smart grid meter, solar inverters, a battery, a car charger, and a heat pump, then
optimises power distribution between them in real time.

## Project Layout

```
src/main/kotlin/
├── Application.kt          # Entry point: starts DataCollector loop + HTTP server
├── DataCollector.kt        # Polls all devices every 5s concurrently
├── ModbusTCPClient.kt      # Shared Modbus TCP wrapper with reconnect logic
├── Messages.kt             # WebSocket message protocol (sealed classes)
├── Sockets.kt              # WebSocket /ws — pushes EMSState to authenticated clients
├── config/
│   └── Config.kt           # Hoplite config data classes; loaded from config.yaml
├── devices/
│   ├── Devices.kt          # Shared types: Watt, Volt, Ampere, DeviceUpdate<T>
│   ├── World.kt            # Holds all live device instances; built from Config
│   ├── battery/            # Battery interface + SMABattery (Modbus TCP)
│   ├── charger/            # Charger interface + Webasto (Modbus TCP, needs keepalive)
│   ├── grid/               # Grid interface + P1Meter (HTTP JSON, HomeWizard)
│   ├── smartConsumer/      # SmartConsumer interface + DaikinHeatpump (Modbus TCP)
│   └── solar/              # Solar interface + SMASolar (Modbus TCP)
├── di/
│   ├── AppComponent.kt     # kotlin-inject @Component
│   └── AppModule.kt        # kotlin-inject @Provides factories
├── ems/
│   ├── EMSState.kt         # Snapshot of all device power values at one point in time
│   ├── EnergyManager.kt    # Reads World state, emits EMSState, applies ControlDecisions
│   ├── Strategy.kt         # Strategy interface + WorldSnapshot + ControlDecisions
│   └── SurplusPriorityStrategy.kt  # Default: heat pump → charger → battery
└── ocpp/
    ├── OcppMessages.kt     # OCPP 1.6J message types (serialisable data classes)
    ├── OcppServer.kt       # WebSocket /ocpp/{chargePointId} endpoint
    └── OcppSessionManager.kt  # OCPP session lifecycle + message dispatch
```

## Device Communication Protocols

| Device | Protocol | Notes |
|--------|----------|-------|
| P1Meter (HomeWizard) | HTTP GET /api/v1/data | JSON; `active_power_w` signed (negative=export) |
| SMA Solar (Sunny Boy) | Modbus TCP port 502, unit 3 | Register 30775 = total AC power (U32, W) |
| SMA Battery (Sunny Boy Storage) | Modbus TCP port 502, unit 3 | 30845=SoC%; 31393=charging W; 31395=discharging W; 40149=target power S32; 40151=control enable (802=on, 803=off) |
| Webasto Unite | Modbus TCP port 502, unit 1 | 1020=current power W; 5004=max current A; 6000=keepalive (write 1 every <30s) |
| Daikin HomeHub | Modbus TCP port 502, unit 1 | 50=power W; 55=SG-Ready mode; 56=max power suggestion |

## Power Sign Convention

All `power: Int` fields in `EMSState` and `Update` use: **negative = producing/exporting, positive = consuming/importing**. Grid power from the P1 meter follows this directly.

## Key Quirks

- **SMASolar**: when the inverter is off (no sunlight), Modbus register 30775 returns `Int.MIN_VALUE`. Treat as 0W.
- **Webasto**: requires a Modbus keepalive write to register 6000 every <30 seconds or it drops remote control.
- **SMABattery**: write 802 to register 40151 to enable Modbus power control *before* writing target power to register 40149. Write 803 to disable.
- **DataCollector**: runs on a fixed-size thread pool (`config.refreshThreads`, default 50). Modbus calls are blocking; they run on `Dispatchers.IO`.

## Build & Run

```bash
./gradlew build          # compile + test
./gradlew run            # start server on port 8080 (requires local hardware)
./gradlew shadowJar      # produce fat jar in build/libs/
```

Config is loaded from `src/main/resources/config.yaml`. Device IPs and credentials live there.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add CLAUDE.md with project navigation guide"
```

---

### Task 2: Write README.md

**Files:**
- Create: `README.md`

- [ ] **Step 1: Create README.md**

```markdown
# EMS Server

A locally-running home energy management system that optimises power distribution
between solar panels, a home battery, a car charger, and a heat pump in real time.

## Hardware

| Device | Model | Protocol |
|--------|-------|----------|
| Smart grid meter | HomeWizard P1 | HTTP |
| Solar inverters (×2) | SMA Sunny Boy | Modbus TCP |
| Home battery | SMA Sunny Boy Storage | Modbus TCP |
| Car charger | Webasto Unite | Modbus TCP |
| Heat pump | Daikin Altherma 3 (HomeHub) | Modbus TCP |

## Prerequisites

- JDK 17+
- Gradle (wrapper included)
- All devices reachable on the local network (see `config.yaml` for IPs)

## Configuration

Edit `src/main/resources/config.yaml`. Key fields:

```yaml
grid:
  type: P1HomeWizard
  gridType: Phase3_230V   # Phase1 | Phase3_230V | Phase3_400V
  host: 192.168.x.x

websocket:
  username: user
  password: password

devices:
  solar:
    - type: SMA_Sunny_Boy
      name: Sunny Boy 4
      host: 192.168.x.x
  battery:
    - type: SMA_Sunny_Boy_Storage
      name: Home Battery
      host: 192.168.x.x
  charger:
    - type: WebastoUnite
      name: Webasto Unite
      host: 192.168.x.x
      chargingCurrent:
        min: 6.0    # minimum amps before charger stops instead of running inefficiently
        max: 32.0
  heatPump:
    - type: DaikinHomeHub
      name: Daikin Altherma 3
      host: 192.168.x.x
```

## Build & Run

```bash
./gradlew build     # compile and run tests
./gradlew run       # start the server on port 8080
```

## API

| Endpoint | Description |
|----------|-------------|
| `GET /` | Health check |
| `WS /ws` | Live power data stream. Send `{"type":"Authenticate","username":"…","password":"…"}` first, then receive `PowerUsageUpdate` messages. |
| `WS /ocpp/{id}` | OCPP 1.6J endpoint for charge points |
| `WS /ocpp/1.6/{id}` | OCPP 1.6J endpoint (alternate path) |

## Optimisation Strategy

The default `SurplusPriorityStrategy` allocates surplus solar power in this order:

1. **Heat pump** — unrestricted when surplus available; throttled on deficit
2. **Car charger** — receives remaining surplus, clamped to configured `[min, max]` amps
3. **Battery** — charges with any remaining surplus; discharges to cover deficit

## Architecture

See `docs/architecture.md` for the data flow diagram and component overview.
See `docs/superpowers/specs/2026-05-22-ems-documentation-and-optimization-design.md` for the
full design spec including pre-refactor issue list.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README with hardware overview and API reference"
```

---

### Task 3: Write docs/architecture.md

**Files:**
- Create: `docs/architecture.md`

- [ ] **Step 1: Create docs/architecture.md**

```markdown
# Architecture

## Data Flow

```
Devices (Modbus TCP / HTTP)
        │
        │  poll every 5s (DataCollector)
        ▼
   World ─────── shared, mutex-protected device state
        │
        │  read each tick (EnergyManager)
        ▼
  WorldSnapshot ──► Strategy.decide() ──► ControlDecisions
        │                                        │
        │  build EMSState                        │  apply to devices
        ▼                                        ▼
   EMSState flow ──► WebSocket /ws ──► client   World.chargers / batteries / smartConsumers
```

## Components

### DataCollector
Polls all devices in `World` concurrently every 5 seconds using a fixed thread pool. Each device stores its latest reading internally (mutex-protected). Does not make any control decisions.

### World
Holds live instances of all devices keyed by name. Built once from `Config` at startup via `World.fromConfig()`. Devices are long-lived; `World` is injected into both `DataCollector` and `EnergyManager`.

### EnergyManager
Runs its own 5-second loop independent of `DataCollector`. Each tick:
1. Reads latest state from all devices via `World`
2. Builds `EMSState` (the aggregate snapshot pushed to clients)
3. In AUTO mode: builds `WorldSnapshot`, calls `strategy.decide()`, applies `ControlDecisions`
4. Emits `EMSState` on `emsStateFlow`

### Strategy
A pure interface: `fun decide(snapshot: WorldSnapshot): ControlDecisions`. Receives a snapshot, returns decisions. No side effects, fully unit-testable. Default implementation: `SurplusPriorityStrategy`.

### OCPP Server
Handles OCPP 1.6J WebSocket connections from physical charge points at `/ocpp/{chargePointId}`. Independent of the EnergyManager optimization loop — charge points report metering data here and receive RemoteStartTransaction / ChangeAvailability commands.

## Threading Model

- Main coroutine scope: started in `Application.main()` via `coroutineScope`
- `DataCollector`: uses `Executors.newFixedThreadPool` + `asCoroutineDispatcher()`
- `EnergyManager.run()`: runs as a coroutine in the main scope
- `ModbusTCPClient.withClient()`: dispatches blocking Modbus calls to `Dispatchers.IO`
- Webasto keepalive: coroutine on `Dispatchers.IO` with its own `SupervisorJob`

## Device Control Interfaces

| Interface | Control method | Implementation |
|-----------|---------------|----------------|
| `Charger` | `setMaxChargerPower(Watt)` | Webasto: writes amps to Modbus register 5004 |
| `Battery` | `setChargingPower(Watt)` | SMABattery: enables Modbus control (reg 40151=802) then sets target power (reg 40149) |
| `SmartConsumer` | `setConsumeMode(ConsumeMode)` | DaikinHeatpump: writes SG-Ready mode to Modbus register 55 |
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: add architecture overview with data flow and component descriptions"
```

---

### Task 4: Add Inline Comments to Device Files

**Files:**
- Modify: `src/main/kotlin/devices/solar/SMASolar.kt`
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt`
- Modify: `src/main/kotlin/devices/charger/Webasto.kt`
- Modify: `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt`
- Modify: `src/main/kotlin/Messages.kt`

- [ ] **Step 1: Add comments to SMASolar.kt**

In `src/main/kotlin/devices/solar/SMASolar.kt`, make these changes:

```kotlin
    // SMA Sunny Boy Modbus: total AC power output, U32, unit W, unit-id 3
    private val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER = 30775
```

```kotlin
        // SMA returns Int.MIN_VALUE when the inverter is off (no sunlight); treat as 0W
        return SolarState(Watt(if (power > 0) power else 0 ))
```

- [ ] **Step 2: Add comments to SMABattery.kt**

In `src/main/kotlin/devices/battery/SMABattery.kt`:

```kotlin
    // State of charge percentage (0-100), U32, unit-id 3. Verify scaling against SMA docs.
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CAPACITY = 30845
    // Total energy charged to battery over its lifetime, Wh (not SoC — do not use for charge %)
    private val MODBUS_INPUT_REGISTER_BATTERY_CHARGE = 31397
    // Current charging power into battery, W, U32
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_CHARGE = 31393
    // Current discharging power from battery, W, U32
    private val MODBUS_INPUT_REGISTER_CURRENT_BATTERY_DISCHARGE = 31395
    // Target charge/discharge power, S32 (positive=charge, negative=discharge), W
    private val MODBUS_OUTPUT_REGISTER_CHARGING_POWER = 40149
    // Write 802 to enable Modbus power control; write 803 to hand control back to the inverter
    private val MODBUS_OUTPUT_REGISTER_CHARGING_CONTROL = 40151
```

- [ ] **Step 3: Add comments to Webasto.kt**

In `src/main/kotlin/devices/charger/Webasto.kt`:

```kotlin
// Webasto Unite requires a keepalive write every <30s to maintain Modbus remote control
private const val MODBUS_REGISTER_KEEPALIVE: Int = 6000
// Maximum charging current in amps (write to set; Webasto clamps to its own max of 32A)
private const val MODBUS_REGISTER_MAX_CURRENT_CHARGING: Int = 5004
// Current total power draw by the charger, W (read-only)
private const val MODBUS_REGISTER_CURRENT_TOTAL_POWER: Int = 1020
```

- [ ] **Step 4: Add comments to DaikinHomeHub.kt**

In `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt`:

```kotlin
// Current total heat pump power consumption, unit 10W (multiply raw value by 10 for W)
private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
// SG-Ready mode: 0=normal, 1=lock (min operation), 2=recommended, 3=max operation
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
// Maximum power suggestion to heat pump when in SG-Ready mode, W
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56
```

- [ ] **Step 5: Add sign convention comment to Messages.kt**

In `src/main/kotlin/Messages.kt`, update the `Update` data class:

```kotlin
// power: negative = producing/exporting, positive = consuming/importing
data class Update(val device: Devices, val power: Int)
```

- [ ] **Step 6: Build to verify no compilation errors**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/devices/solar/SMASolar.kt \
        src/main/kotlin/devices/battery/SMABattery.kt \
        src/main/kotlin/devices/charger/Webasto.kt \
        src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt \
        src/main/kotlin/Messages.kt
git commit -m "docs: add inline comments for Modbus registers, quirks, and sign convention"
```

---

## Phase 2: Refactor

### Task 5: Make ModbusTCPClient.withClient() a Suspend Function

**Files:**
- Modify: `src/main/kotlin/ModbusTCPClient.kt`

The current `withClient` is not a suspend function and uses `synchronized(client)` — which synchronises on a reassignable `var` (a bug). Fix: use a dedicated lock object and dispatch to `Dispatchers.IO` so blocking Modbus calls don't pin coroutine threads.

- [ ] **Step 1: Replace ModbusTCPClient.kt**

```kotlin
package io.konektis

import com.digitalpetri.modbus.client.ModbusTcpClient
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModbusTCPClient(private val host: String) {

    private var client = makeClient()
    private val lock = Any()

    private fun makeClient(): ModbusTcpClient {
        val transport = NettyTcpClientTransport.create { cfg: NettyClientTransportConfig.Builder ->
            cfg.hostname = host
            cfg.port = 502
        }
        val modbusClient = ModbusTcpClient.create(transport)
        modbusClient.connect()
        return modbusClient
    }

    suspend fun <T> withClient(f: (client: ModbusTcpClient) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!client.isConnected) client.connect()
            try {
                f(client)
            } catch (e: Exception) {
                client = makeClient()
                f(client)
            }
        }
    }
}
```

- [ ] **Step 2: Build — expect compilation errors in device files**

```bash
./gradlew compileKotlin 2>&1 | grep "error:"
```

Expected: errors in `SMASolar.kt`, `SMABattery.kt`, `Webasto.kt`, `DaikinHomeHub.kt` because their private helper functions are not yet `suspend`.

- [ ] **Step 3: Fix SMASolar.kt — make getSolarState() suspend**

In `src/main/kotlin/devices/solar/SMASolar.kt`, change:

```kotlin
    private suspend fun getSolarState(): SolarState {
```

(add `suspend` to the `private fun getSolarState()` declaration — no other change needed in the body)

- [ ] **Step 4: Fix SMABattery.kt — make all private helpers suspend**

In `src/main/kotlin/devices/battery/SMABattery.kt`, add `suspend` to:

```kotlin
    private suspend fun getCharge() : ULong {
    private suspend fun getCurrentCharge() : UInt {
    private suspend fun getCurrentDischarge() : UInt {
    private suspend fun getCapacity() : UInt {
    suspend fun getInternalState(): BatteryState {
```

(`setChargingPower` is already `suspend` — no change needed there.)

- [ ] **Step 5: Fix Webasto.kt — make getCurrentPowerUsage() suspend**

In `src/main/kotlin/devices/charger/Webasto.kt`, change:

```kotlin
    private suspend fun getCurrentPowerUsage(): Int {
```

- [ ] **Step 6: Fix DaikinHomeHub.kt — make private DaikinHomeHub.getState() suspend**

In `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt`, the private inner class `DaikinHomeHub` has:

```kotlin
    suspend fun getState(): SmartConsumerState {
```

(add `suspend` to `fun getState()` in the private class — the outer `DaikinHeatpump` calls it from a `mutex.withLock` suspend lambda, so no further changes needed)

- [ ] **Step 7: Build to verify all errors are resolved**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Run existing tests**

```bash
./gradlew test
```

Expected: all tests that don't require hardware pass (OCPP tests, config tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/ModbusTCPClient.kt \
        src/main/kotlin/devices/solar/SMASolar.kt \
        src/main/kotlin/devices/battery/SMABattery.kt \
        src/main/kotlin/devices/charger/Webasto.kt \
        src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt
git commit -m "refactor: make ModbusTCPClient.withClient() suspend, dispatch Modbus calls to IO"
```

---

### Task 6: Fix Webasto Keepalive — Replace thread/runBlocking with Coroutine

**Files:**
- Modify: `src/main/kotlin/devices/charger/Webasto.kt`

The current `thread { runBlocking { keepAliveLoop() } }` creates a dedicated OS thread for a simple periodic task. Replace with a coroutine on `Dispatchers.IO`.

- [ ] **Step 1: Update Webasto.kt keepalive initialisation**

Replace the `thread` field with a `CoroutineScope` and `init` block. The full updated class header section (imports + fields + init) should be:

```kotlin
package io.konektis.devices.charger

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest
import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

// ...

class Webasto(val host: String) : Klogging, Charger {

    private var client = ModbusTCPClient(host)
    private val mutex = Mutex()
    private var internalState: DeviceUpdate<ChargerState>? = null
    private val keepAliveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        keepAliveScope.launch { keepAliveLoop() }
    }
    // rest of class unchanged
```

Remove the old `private val thread = thread { runBlocking { keepAliveLoop() } }` field and its imports (`kotlin.concurrent.thread`, `kotlinx.coroutines.runBlocking`).

- [ ] **Step 2: Build and verify**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/devices/charger/Webasto.kt
git commit -m "refactor: replace Webasto keepalive thread/runBlocking with coroutine"
```

---

### Task 7: Complete SMABattery State and Fix Control Register

**Files:**
- Modify: `src/main/kotlin/devices/battery/SMABattery.kt`

Two fixes: (1) `getInternalState()` currently returns stub zeros — make it return real charge and net power. (2) `setChargingPower()` writes 801 to the control register but the comment says 802 enables Modbus control — fix it.

- [ ] **Step 1: Add setChargingPower() to Battery interface**

In `src/main/kotlin/devices/battery/Battery.kt`, add the control method so `EnergyManager` can call it without casting:

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
}
```

- [ ] **Step 2: Fix getInternalState() in SMABattery.kt**

Replace the `getInternalState()` body:

```kotlin
    suspend fun getInternalState(): BatteryState {
        val soc = getCapacity()          // SoC percent (0-100) from register 30845
        val charging = getCurrentCharge()    // charging power W from register 31393
        val discharging = getCurrentDischarge() // discharging power W from register 31395
        val netPower = charging.toInt() - discharging.toInt() // positive=charging, negative=discharging
        return BatteryState(soc.toUShort(), Watt(netPower))
    }
```

Also remove the `println` debug statement and the `getCharge()` call (lifetime Wh register) from this method since lifetime charge is not needed for `BatteryState`. The `getCharge()` function can remain in the file but is unused for now.

- [ ] **Step 2: Fix control register value in setChargingPower()**

In `setChargingPower`, find:

```kotlin
Endian.Big.pack(801, bytes, 0)
```

Replace with:

```kotlin
// 802 enables Modbus power control; 803 disables it (returns control to inverter)
Endian.Big.pack(802, bytes, 0)
```

- [ ] **Step 3: Build and verify**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/devices/battery/Battery.kt \
        src/main/kotlin/devices/battery/SMABattery.kt
git commit -m "fix: add setChargingPower to Battery interface; complete SMABattery state; fix control register 801->802"
```

---

### Task 8: Implement DaikinHeatpump.setConsumeMode()

**Files:**
- Modify: `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt`

`setConsumeMode()` currently throws `TODO()`. Implement it using the SG-Ready Modbus registers already identified in the device (register 55 = mode, register 56 = max power).

- [ ] **Step 1: Add write capability to DaikinHomeHub private class**

In the private `DaikinHomeHub` class, add a `setConsumeMode` function:

```kotlin
    suspend fun setConsumeMode(consumeMode: ConsumeMode) {
        val (mode, maxPower) = when (consumeMode) {
            is ConsumeMode.Unrestricted -> Pair(0, 0)
            is ConsumeMode.SuggestConsumeUpTo -> Pair(1, consumeMode.power.value)
        }
        client.withClient { c ->
            val modeBytes = ByteArray(2)
            modeBytes[0] = (mode shr 8).toByte()
            modeBytes[1] = mode.toByte()
            c.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_HOLDING_REGISTER_SMART_GRID, mode))
        }
        if (maxPower > 0) {
            client.withClient { c ->
                c.writeSingleRegister(1, WriteSingleRegisterRequest(MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER, maxPower))
            }
        }
    }
```

Add the import `import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest` at the top of the file.

- [ ] **Step 2: Implement setConsumeMode() in DaikinHeatpump outer class**

Replace the `TODO("Not yet implemented")` body:

```kotlin
    override suspend fun setConsumeMode(consumeMode: ConsumeMode) {
        mutex.withLock {
            homeHub.setConsumeMode(consumeMode)
        }
    }
```

- [ ] **Step 3: Build and verify**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt
git commit -m "feat: implement DaikinHeatpump.setConsumeMode() via SG-Ready Modbus registers"
```

---

### Task 9: Move WebSocket Credentials to Config

**Files:**
- Modify: `src/main/kotlin/config/Config.kt`
- Modify: `src/main/resources/config.yaml`
- Modify: `src/main/kotlin/Sockets.kt`
- Modify: `src/main/kotlin/Application.kt`

- [ ] **Step 1: Add WebSocketConfig to Config.kt**

In `src/main/kotlin/config/Config.kt`, add before `Config`:

```kotlin
@Serializable
data class WebSocketConfig(val username: String, val password: String)
```

And update `Config` to include it (with a default so existing configs without the field still load):

```kotlin
@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val refreshThreads: Int = 50
)
```

- [ ] **Step 2: Add websocket section to config.yaml**

In `src/main/resources/config.yaml`, add after the `ocpp` block:

```yaml
websocket:
  username: user
  password: password
```

- [ ] **Step 3: Update Sockets.kt to accept WebSocketConfig**

Change `configureSockets` signature and replace hardcoded credential check:

```kotlin
fun Application.configureSockets(emsStateFlow: Flow<EMSState>, wsConfig: WebSocketConfig) {
```

Replace:
```kotlin
                            if (message.username == "user" && message.password == "password") {
```

With:
```kotlin
                            if (message.username == wsConfig.username && message.password == wsConfig.password) {
```

Add import at top of `Sockets.kt`:
```kotlin
import io.konektis.config.WebSocketConfig
```

- [ ] **Step 4: Update Application.kt module function and call site**

Change `Application.module`:

```kotlin
fun Application.module(emsStateFlow: Flow<EMSState>, wsConfig: WebSocketConfig) {
    configureSecurity()
    configureAdministration()
    configureSockets(emsStateFlow, wsConfig)
    configureOcppServer()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
```

Update the `embeddedServer` call in `Main.main`:

```kotlin
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(energyManager.emsStateFlow, config.websocket)
                }
                server.start(wait = true)
            }
```

Add import in `Application.kt`:
```kotlin
import io.konektis.config.WebSocketConfig
```

- [ ] **Step 5: Update ApplicationTest.kt if it calls module()**

Check `src/test/kotlin/ApplicationTest.kt`. If it calls `application { module(...) }`, update it to pass a `WebSocketConfig`:

```kotlin
application { module(MutableStateFlow(EMSState(null,null,null,null,null,null,null)), WebSocketConfig("user","password")) }
```

- [ ] **Step 6: Build and run tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/config/Config.kt \
        src/main/resources/config.yaml \
        src/main/kotlin/Sockets.kt \
        src/main/kotlin/Application.kt \
        src/test/kotlin/ApplicationTest.kt
git commit -m "refactor: move WebSocket credentials from hardcoded strings to config.yaml"
```

---

### Task 10: Refactor EnergyManager to Read from World

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`
- Modify: `src/main/kotlin/Application.kt`

This is the core refactor. `EnergyManager` currently creates its own device instances inside `run(config)`. Replace with a constructor-injected `World`, and a `run()` that reads from it each tick.

- [ ] **Step 1: Rewrite EnergyManager.kt**

```kotlin
package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.devices.Watt
import io.konektis.devices.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration

enum class Mode {
    AUTO, MANUAL
}

class EnergyManager(
    private val world: World,
    private val config: Config
) : Klogging {

    var mode = Mode.AUTO
    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))

    suspend fun run() {
        while (true) {
            emsStateFlow.value = buildEMSState()
            delay(Duration.ofSeconds(5))
        }
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
}
```

- [ ] **Step 2: Update AppModule.kt to inject World and Config**

Replace `provideEnergyManager`:

```kotlin
    @Provides
    fun provideEnergyManager(world: World, config: Config): EnergyManager = EnergyManager(world, config)
```

- [ ] **Step 3: Re-enable energyManager.run() in Application.kt**

In `Main.main()`, uncomment:

```kotlin
            launch { energyManager.run() }
```

- [ ] **Step 4: Build and run tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt \
        src/main/kotlin/di/AppModule.kt \
        src/main/kotlin/Application.kt
git commit -m "refactor: inject World into EnergyManager, populate full EMSState, re-enable run loop"
```

---

## Phase 3: Optimization

### Task 11: Define Strategy Interface, WorldSnapshot, ControlDecisions

**Files:**
- Create: `src/main/kotlin/ems/Strategy.kt`

- [ ] **Step 1: Create Strategy.kt**

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
    val chargerMinAmps: Int,      // from config: minimum amps before charger stops
    val chargerMaxAmps: Int       // from config: maximum amps
)

data class ControlDecisions(
    val chargerMaxAmps: Int?,             // null = no change
    val batteryTargetPower: Watt?,        // null = no change; positive=charge, negative=discharge
    val heatpumpConsumeMode: ConsumeMode? // null = no change
)

interface Strategy {
    fun decide(snapshot: WorldSnapshot): ControlDecisions
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ems/Strategy.kt
git commit -m "feat: add Strategy interface with WorldSnapshot and ControlDecisions"
```

---

### Task 12: Write Failing Tests for SurplusPriorityStrategy

**Files:**
- Create: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurplusPriorityStrategyTest {

    private val strategy = SurplusPriorityStrategy()

    private fun snapshot(
        gridPower: Int,       // negative=export, positive=import
        solarPower: Int = 0,
        chargerPower: Int = 0,
        batteryPower: Int = 0,
        heatpumpPower: Int = 0,
        batteryCharge: UShort = 50u,
        chargerMinAmps: Int = 6,
        chargerMaxAmps: Int = 32
    ) = WorldSnapshot(
        gridPower = Watt(gridPower),
        solarPower = Watt(solarPower),
        batteryCharge = batteryCharge,
        batteryPower = Watt(batteryPower),
        chargerPower = Watt(chargerPower),
        heatpumpPower = Watt(heatpumpPower),
        chargerMinAmps = chargerMinAmps,
        chargerMaxAmps = chargerMaxAmps
    )

    @Test
    fun `large solar surplus — charger gets max amps, battery absorbs remainder`() {
        // Exporting 2000W (gridPower=-2000), charger currently at 2000W
        // Available = 2000 (charger) - (-2000) (grid export) = 4000W
        // 4000W / 230V = 17.39A -> 17A for charger
        // remainder = 4000 - 17*230 = 4000 - 3910 = 90W to battery
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(17, decisions.chargerMaxAmps)
        assertEquals(Watt(90), decisions.batteryTargetPower)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `importing from grid — charger should reduce`() {
        // Importing 500W (gridPower=500), charger at 3000W
        // Available = 3000 - 500 = 2500W
        // 2500 / 230 = 10.86A -> 10A
        // remainder = 2500 - 10*230 = 2500 - 2300 = 200W to battery
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(10, decisions.chargerMaxAmps)
        assertEquals(Watt(200), decisions.batteryTargetPower)
    }

    @Test
    fun `surplus below charger minimum — charger stops, battery charges`() {
        // Exporting only 200W (gridPower=-200), charger at 0W
        // Available = 0 - (-200) = 200W
        // 200 / 230 = 0.87A < 6A minimum -> charger = 0A
        // All 200W goes to battery
        val decisions = strategy.decide(snapshot(gridPower = -200, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(Watt(200), decisions.batteryTargetPower)
    }

    @Test
    fun `zero solar and importing — charger stops, battery covers deficit`() {
        // Importing 1500W (gridPower=1500), charger at 0W, no solar
        // Available = 0 - 1500 = -1500W (deficit)
        // Charger = 0A (deficit)
        // Battery = -1500W (discharge to cover)
        val decisions = strategy.decide(snapshot(gridPower = 1500, chargerPower = 0))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(Watt(-1500), decisions.batteryTargetPower)
    }

    @Test
    fun `charger surplus clamped to max amps`() {
        // Exporting 10000W (gridPower=-10000), charger at 6000W
        // Available = 6000 + 10000 = 16000W
        // 16000 / 230 = 69.5A -> clamped to 32A max
        // remainder = 16000 - 32*230 = 16000 - 7360 = 8640W to battery
        val decisions = strategy.decide(snapshot(gridPower = -10000, chargerPower = 6000, chargerMaxAmps = 32))
        assertEquals(32, decisions.chargerMaxAmps)
        assertEquals(Watt(8640), decisions.batteryTargetPower)
    }

    @Test
    fun `heat pump unrestricted when surplus available`() {
        val decisions = strategy.decide(snapshot(gridPower = -500, chargerPower = 0))
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }

    @Test
    fun `heat pump throttled when in deficit`() {
        // Importing 800W, heat pump using 1000W
        val decisions = strategy.decide(snapshot(gridPower = 800, chargerPower = 0, heatpumpPower = 1000))
        val mode = decisions.heatpumpConsumeMode
        assertTrue(mode is ConsumeMode.SuggestConsumeUpTo)
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure (SurplusPriorityStrategy doesn't exist yet)**

```bash
./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest" 2>&1 | tail -20
```

Expected: compilation error — `SurplusPriorityStrategy` unresolved.

- [ ] **Step 3: Commit the failing tests**

```bash
git add src/test/kotlin/ems/SurplusPriorityStrategyTest.kt
git commit -m "test: add failing unit tests for SurplusPriorityStrategy"
```

---

### Task 13: Implement SurplusPriorityStrategy

**Files:**
- Create: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`

- [ ] **Step 1: Create SurplusPriorityStrategy.kt**

```kotlin
package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.math.max

class SurplusPriorityStrategy : Strategy {

    override fun decide(snapshot: WorldSnapshot): ControlDecisions {
        // Available power = what charger + battery currently use, minus any grid import (or plus grid export)
        // gridPower: negative = exporting (surplus), positive = importing (deficit)
        val available = snapshot.chargerPower.value + snapshot.batteryPower.value - snapshot.gridPower.value

        // Heat pump: unrestricted when surplus, throttled to currently-available headroom on deficit
        val heatpumpMode: ConsumeMode = if (available >= 0) {
            ConsumeMode.Unrestricted
        } else {
            val headroom = max(0, snapshot.heatpumpPower.value + available)
            ConsumeMode.SuggestConsumeUpTo(Watt(headroom))
        }

        // Car charger: assign available power in amps, clamped to [min, max]
        val chargerAmps = when {
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

        // Battery: remaining surplus charges; remaining deficit discharges
        val chargerConsumption = chargerAmps * 230
        val batteryTarget = Watt(available - chargerConsumption)

        return ControlDecisions(
            chargerMaxAmps = chargerAmps,
            batteryTargetPower = batteryTarget,
            heatpumpConsumeMode = heatpumpMode
        )
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"
```

Expected: all 7 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ems/SurplusPriorityStrategy.kt
git commit -m "feat: implement SurplusPriorityStrategy (heat pump → charger → battery priority)"
```

---

### Task 14: Wire Strategy into EnergyManager

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`

- [ ] **Step 1: Update EnergyManager to inject and use Strategy**

Add `strategy: Strategy` constructor parameter and update `run()` to apply `ControlDecisions`:

```kotlin
package io.konektis.ems

import io.klogging.Klogging
import io.konektis.config.Config
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.time.delay
import java.time.Duration

enum class Mode {
    AUTO, MANUAL
}

class EnergyManager(
    private val world: World,
    private val config: Config,
    private val strategy: Strategy
) : Klogging {

    var mode = Mode.AUTO
    val emsStateFlow = MutableStateFlow(EMSState(null, null, null, null, null, null, null))

    suspend fun run() {
        while (true) {
            val emsState = buildEMSState()
            emsStateFlow.value = emsState

            if (mode == Mode.AUTO) {
                val snapshot = buildWorldSnapshot(emsState)
                if (snapshot == null) {
                    logger.warn("Incomplete device state — skipping optimization tick")
                } else {
                    applyDecisions(strategy.decide(snapshot))
                }
            }

            delay(Duration.ofSeconds(5))
        }
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
            world.chargers.values.forEach { charger ->
                try {
                    charger.setMaxChargerPower(Watt(amps * 230))
                } catch (e: Exception) {
                    logger.error("Failed to set charger power: $e")
                }
            }
        }
        decisions.batteryTargetPower?.let { power ->
            world.batteries.values.forEach { battery ->
                try {
                    battery.setChargingPower(power)
                } catch (e: Exception) {
                    logger.error("Failed to set battery power: $e")
                }
            }
        }
        decisions.heatpumpConsumeMode?.let { mode ->
            world.smartConsumers.values.forEach { consumer ->
                try {
                    consumer.setConsumeMode(mode)
                } catch (e: Exception) {
                    logger.error("Failed to set heat pump mode: $e")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Update AppModule.kt to provide Strategy and inject into EnergyManager**

```kotlin
    @Provides
    fun provideStrategy(): Strategy = SurplusPriorityStrategy()

    @Provides
    fun provideEnergyManager(world: World, config: Config, strategy: Strategy): EnergyManager =
        EnergyManager(world, config, strategy)
```

Add import:
```kotlin
import io.konektis.ems.Strategy
import io.konektis.ems.SurplusPriorityStrategy
```

- [ ] **Step 3: Build and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt \
        src/main/kotlin/di/AppModule.kt
git commit -m "feat: wire SurplusPriorityStrategy into EnergyManager, apply control decisions each tick"
```

---

### Task 15: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew test
```

Expected: all tests pass. Note: hardware integration tests (`SMABatteryTest`, `SMASolarTest`) will fail unless devices are reachable — this is expected in CI.

- [ ] **Step 2: Verify build artifact**

```bash
./gradlew shadowJar
ls -lh build/libs/
```

Expected: fat jar present in `build/libs/`.

- [ ] **Step 3: Final commit if any loose files**

```bash
git status
```

If clean: no action needed. If any files modified: stage and commit with appropriate message.
