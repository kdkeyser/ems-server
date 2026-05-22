# EMS Documentation & Optimization Design

**Date:** 2026-05-22  
**Status:** Approved

---

## Overview

This document captures the current state of the `ems-server` codebase, defines a target clean architecture, and specifies a three-phase plan to: (1) document the current state, (2) refactor to the clean architecture, and (3) implement real-time energy optimization.

---

## Current State Assessment

### Hardware

| Device | Type | Protocol | IP |
|---|---|---|---|
| Grid meter | HomeWizard P1 | HTTP JSON | 192.168.129.17 |
| Solar inverter 1 | SMA Sunny Boy 4 | Modbus TCP | 192.168.129.15 |
| Solar inverter 2 | SMA Sunny Boy 5 | Modbus TCP | 192.168.129.16 |
| Battery | SMA Sunny Boy Storage | Modbus TCP | 192.168.129.18 |
| Car charger | Webasto Unite | Modbus TCP | 192.168.129.19 |
| Heat pump | Daikin Altherma 3 (HomeHub) | Modbus TCP | 192.168.129.12 |

### What Works

- Device abstractions with clean interfaces (`Grid`, `Solar`, `Battery`, `Charger`, `SmartConsumer`)
- Hoplite YAML config loading (`config.yaml`)
- kotlin-inject DI wiring (`AppComponent` / `AppModule`)
- `DataCollector` polling all devices concurrently every 5 seconds
- OCPP 1.6J WebSocket server handling BootNotification, Heartbeat, Authorize, Start/StopTransaction, StatusNotification, MeterValues, DataTransfer
- WebSocket `/ws` endpoint pushing `EMSState` updates to client app
- SMA Solar Modbus read (register 30775, handles NaN-as-negative quirk)
- P1Meter HTTP read (active power + voltage)
- Webasto Modbus read (current power) and write (max current) with keepalive

### Known Issues / Stubs

| Issue | Location | Impact |
|---|---|---|
| `energyManager.run(config)` is commented out | `Application.kt:58` | Optimization loop never runs |
| `EnergyManager` creates its own device instances | `EnergyManager.kt:67-76` | Disconnected from `DataCollector`'s `World` |
| `SMABattery.getInternalState()` returns zeros | `SMABattery.kt:98` | Battery state always null in EMSState |
| `EMSState.solarPower` never populated | `EnergyManager.kt:55-64` | Solar data absent from WebSocket feed |
| `EMSState.batteryPower/batteryCharge` never populated | `EnergyManager.kt:55-64` | Battery data absent from WebSocket feed |
| `DaikinHeatpump.setConsumeMode()` is TODO | `DaikinHomeHub.kt:44` | Heat pump cannot be controlled |
| `ModbusTCPClient` uses `synchronized()` | `ModbusTCPClient.kt:23` | Blocks coroutine threads |
| Webasto keepalive uses `thread { runBlocking {} }` | `Webasto.kt:28-33` | Mixed threading models |
| Hardcoded credentials `user`/`password` | `Sockets.kt:61` | Not configurable |
| Battery control register writes 801 instead of 802 | `SMABattery.kt:57` | Modbus control mode not enabled correctly |
| `EnergyManager` available-power formula incorrect | `EnergyManager.kt:89` | `chargerPower - gridPower` ignores solar |
| Package name inconsistencies | `SMASolar.kt`, `DaikinHomeHub.kt` | Minor, cosmetic |

---

## Target Architecture

### Data Flow

```
Devices (Modbus TCP / HTTP)
        ↓  poll every 5s
   DataCollector  →  World (shared device state, mutex-protected)
                          ↓  read each optimization tick
                    EnergyManager  →  ControlDecisions  →  device.set*() calls
                          ↓  emit
                    EMSState flow  →  WebSocket /ws  →  client app
```

### Key Structural Changes

1. **EnergyManager injected with `World`** via DI — reads device state from the shared `World` instead of owning private device instances. The `run(config)` method is replaced by `run(world: World)`.

2. **`EMSState` fully populated** from `World` state on each optimization tick — all fields (solar, battery, grid, charger, heatpump) are set.

3. **`SMABattery` state completed** — `getInternalState()` returns real `BatteryState` with charge percentage (SoC register — exact Modbus address to be confirmed against SMA documentation during implementation) and current net power (register 31393 charge minus register 31395 discharge).

4. **`DaikinHeatpump.setConsumeMode()` implemented** — writes to Modbus holding register 55 (smart-grid mode) and register 56 (max power) to signal the heat pump.

5. **`ModbusTCPClient` coroutine-safe** — `synchronized()` replaced with a coroutine `Mutex`; blocking Modbus calls dispatched on `Dispatchers.IO`.

6. **Webasto keepalive coroutine** — `thread { runBlocking { } }` replaced with a coroutine launched on `Dispatchers.IO` from a `CoroutineScope`.

7. **`Strategy` interface** — optimization logic extracted from `EnergyManager` into a pluggable interface:
   ```kotlin
   interface Strategy {
       fun decide(snapshot: WorldSnapshot): ControlDecisions
   }
   ```
   `EnergyManager` holds a `Strategy` reference and calls it each tick.

8. **Credentials from config** — `username`/`password` for the WebSocket endpoint moved to `config.yaml`.

### What Stays Unchanged

- Device interfaces (`Grid`, `Solar`, `Battery`, `Charger`, `SmartConsumer`)
- OCPP 1.6J server implementation
- `config.yaml` format (additions only, no breaking changes)
- WebSocket message protocol (`Message`, `ClientMessage`, `Devices` enum)
- Ktor/Netty setup and module structure

---

## Optimization Strategy

### Power Balance

```
availablePower = solarPower - gridImport
                           (gridPower is signed: negative = export, positive = import)
```

`availablePower > 0` means surplus solar available to allocate.

### Default Strategy: `SurplusPriorityStrategy`

Priority order for surplus power:

1. **Heat pump** — set `ConsumeMode.Unrestricted` when surplus ≥ heat pump min power; set `ConsumeMode.SuggestConsumeUpTo(availablePower)` otherwise.
2. **Car charger** — assign remaining surplus clamped to `[chargingCurrent.min, chargingCurrent.max]`. If surplus below minimum, stop charging (set max current to 0A via Modbus) rather than charge inefficiently.
3. **Battery** — direct remaining surplus to charge; draw from battery to cover deficit via `setChargingPower()`.

### Strategy Interface

```kotlin
data class WorldSnapshot(
    val gridPower: Watt,          // signed: negative = export
    val solarPower: Watt,
    val batteryCharge: UShort,    // percent 0-100
    val batteryPower: Watt,
    val chargerPower: Watt,
    val heatpumpPower: Watt
)

data class ControlDecisions(
    val chargerMaxCurrent: Int?,         // null = no change
    val batteryTargetPower: Watt?,       // null = no change
    val heatpumpConsumeMode: ConsumeMode?
)

interface Strategy {
    fun decide(snapshot: WorldSnapshot): ControlDecisions
}
```

The `Strategy` interface is also the extension point for the future Android app's configurable strategies.

### Modes

- `AUTO` — `EnergyManager` runs the configured `Strategy` each tick
- `MANUAL` — `EnergyManager` passes through explicit commands from the WebSocket client (existing `Mode` enum retained)

---

## Error Handling

- Each device read is wrapped in a try/catch; on failure the last known good `DeviceUpdate` is retained and a warning is logged.
- If a device command fails, it is logged and retried on the next tick. No silent swallowing.
- If all device reads fail for a configurable timeout, `EnergyManager` enters a safe state (stop charging, battery neutral).

---

## Testing

- `Strategy` implementations are pure functions (`WorldSnapshot → ControlDecisions`) — unit-tested with no I/O.
- Device implementations (`SMABattery`, `SMASolar`, etc.) remain integration-tested against real hardware.
- `EnergyManager` loop is tested by injecting a mock `Strategy` and mock `World`.
- Credentials and config values used in tests are documented.

---

## Three-Phase Plan

### Phase 1 — Document Current State

- Write `CLAUDE.md` (AI navigation: packages, protocols, DI, known issues)
- Write `README.md` (developer onboarding: hardware, build, run, current limitations)
- Write `docs/architecture.md` (this target architecture + gap analysis)
- Add inline comments for non-obvious code: Modbus register maps, NaN quirk in SMASolar, battery control register sequence, sign convention on gridPower, Webasto keepalive requirement

### Phase 2 — Refactor

- Complete `SMABattery.getInternalState()` with real values
- Implement `DaikinHeatpump.setConsumeMode()`
- Replace `ModbusTCPClient` `synchronized()` with coroutine `Mutex` + `Dispatchers.IO`
- Replace Webasto keepalive thread with a coroutine
- Inject `World` into `EnergyManager` via DI; remove private device instances from `run()`
- Populate all `EMSState` fields from `World`
- Move WebSocket credentials to `config.yaml`
- Fix battery control register value (801 → 802)
- Re-enable `energyManager.run()` in `Application.kt`

### Phase 3 — Optimization

- Define `WorldSnapshot`, `ControlDecisions`, and `Strategy` interface
- Implement `SurplusPriorityStrategy`
- Wire `Strategy` into `EnergyManager`
- Unit-test `SurplusPriorityStrategy` with representative scenarios
- Smoke-test end-to-end with real hardware

---

## Out of Scope (Future)

- Forecasting / time-of-use pricing
- Android app for strategy configuration
- Multi-charger or multi-battery support
- Historical data persistence beyond what H2 already provides
