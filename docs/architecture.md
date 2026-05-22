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
