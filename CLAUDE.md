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
│   ├── charger/            # Charger interface + Webasto (Modbus TCP) + OcppCharger (OCPP 1.6J)
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
├── history/                # ClickHouse history collector + GET /history query endpoint
└── ocpp/
    ├── OcppMessages.kt     # OCPP 1.6J message types (serialisable data classes)
    ├── OcppServer.kt       # WebSocket /ocpp/{chargePointId} endpoint (subprotocol ocpp1.6)
    ├── OcppService.kt      # DI singleton: live session state (StateFlow), inbound handlers,
    │                       #   request/response correlation, outbound commands, capability detection
    ├── OcppWebUi.kt        # /ocpp-ui config + status web page, live WS, REST API
    └── db/                 # SQLite (Exposed): charge-point allow-list, idTags, settings, transactions
```

See [docs/adding-an-ocpp-charger.md](docs/adding-an-ocpp-charger.md) for how to configure and connect an OCPP charger.
See [docs/adding-clickhouse-history.md](docs/adding-clickhouse-history.md) for the ClickHouse power-history store and the `GET /history` API.
See [docs/adding-grafana-dashboards.md](docs/adding-grafana-dashboards.md) for the LAN-only Grafana dashboards over the ClickHouse history.

## Device Communication Protocols

| Device | Protocol | Notes |
|--------|----------|-------|
| P1Meter (HomeWizard) | HTTP GET /api/v1/data | JSON; `active_power_w` signed (negative=export) |
| SMA Solar (Sunny Boy) | Modbus TCP port 502, unit 3 | Register 30775 = total AC power (U32, W) |
| SMA Battery (Sunny Boy Storage) | Modbus TCP port 502, unit 3 | 30845=SoC%; 31393=charging W; 31395=discharging W; 40149=target power S32 (**inverted sign**: +=discharge, −=charge); 40151=control enable (802=on, 803=off) |
| Webasto Unite | Modbus TCP port 502, unit 1 | 1020=current power W; 5004=max current A; 6000=keepalive (write 1 every <30s) |
| Daikin HomeHub | Modbus TCP port 502, unit 1 | 50=power W; 55=SG-Ready mode; 56=max power suggestion |
| OCPP charger | WebSocket /ocpp/{id}, subprotocol ocpp1.6 | Charger dials in; power from MeterValues `Power.Active.Import`; throttle via SetChargingProfile (needs SmartCharging profile). See [docs/adding-an-ocpp-charger.md](docs/adding-an-ocpp-charger.md) |

## Power Sign Convention

All `power: Int` fields in `EMSState` and `Update` use: **negative = producing/exporting, positive = consuming/importing**. Grid power from the P1 meter follows this directly.

**Exception — solar:** `solarPower` is reported as **positive watts while producing** (SMA register 30775 is an unsigned production value, and the Android app renders it as-is). Do not feed `solarPower` into control math expecting the negative-producing convention.

## Key Quirks

- **SMASolar**: when the inverter is off (no sunlight), Modbus register 30775 returns `Int.MIN_VALUE`. Treat as 0W.
- **Webasto**: requires a Modbus keepalive write to register 6000 every <30 seconds or it drops remote control.
- **SMABattery**: write 802 to register 40151 to enable Modbus power control *before* writing
  target power to register 40149. Register 40149 uses the **opposite sign of our Watt convention**:
  on this inverter a positive setpoint *discharges* and a negative one *charges*, so
  `setChargingPower()` negates at the write boundary (confirmed on hardware — a discharge command
  was charging the battery before the flip). Write 803 to release control back to the inverter. The EMS
  holds control only while steering and releases (803) on MANUAL mode, on grid/battery data
  going blind for ~30s, and on graceful shutdown. The inverter's own watchdog is too slow
  (≥15 min) to rely on for crash recovery.
- **DataCollector**: runs on a fixed-size thread pool (`config.refreshThreads`, default 50). Modbus calls are blocking; they run on `Dispatchers.IO`.
- **Solar charging sessions**: `SurplusPriorityStrategy` gates session start/stop with hysteresis — start after ~60 s of surplus ≥ min current (6 A × 230 V), stop after ~5 min of sustained deficit (< half the min). A plugged-in car in SOLAR mode does not charge at the min floor all night.

## Build & Run

```bash
./gradlew build          # compile + test
./gradlew run            # start server on port 8080 (requires local hardware)
./gradlew shadowJar      # produce fat jar in build/libs/
```

Config is loaded from `src/main/resources/config.yaml`. Device IPs and credentials live there.
