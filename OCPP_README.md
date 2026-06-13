# OCPP 1.6J Server Implementation

This project includes an OCPP 1.6J (Open Charge Point Protocol) Central System implementation
built on Ktor WebSockets. Electric-vehicle charge points dial *in* to the server; the EMS reads
their power and throttles their charging current to follow solar surplus.

> **Operator guide.** For connecting and configuring a real charger (config fields, the `/ocpp-ui`
> web page, capability detection, the Webasto hybrid fallback, troubleshooting), see
> [docs/adding-an-ocpp-charger.md](docs/adding-an-ocpp-charger.md). This file is the
> protocol/implementation reference for developers.

## Components

| File | Responsibility |
|------|----------------|
| [`ocpp/OcppMessages.kt`](src/main/kotlin/ocpp/OcppMessages.kt) | Serializable data classes for OCPP 1.6J message types (Core + Smart Charging + Remote Trigger), and the `Action` enum. |
| [`ocpp/OcppServer.kt`](src/main/kotlin/ocpp/OcppServer.kt) | Ktor WebSocket endpoints, subprotocol negotiation, framing of the JSON-RPC array messages, and routing to the service. |
| [`ocpp/OcppService.kt`](src/main/kotlin/ocpp/OcppService.kt) | DI singleton holding live session state (`StateFlow`); inbound handlers, request/response correlation, outbound commands, and runtime capability detection. |
| [`ocpp/OcppWebUi.kt`](src/main/kotlin/ocpp/OcppWebUi.kt) | `/ocpp-ui` config + status web page, its live WebSocket, and the REST API. |
| [`ocpp/db/`](src/main/kotlin/ocpp/db/) | SQLite persistence (Exposed): charge-point allow-list, idTags, per-charger control settings, and transactions. |

## WebSocket Endpoints

The server exposes two endpoints, both requiring the `ocpp1.6` subprotocol:

- `/ocpp/{chargePointId}` — standard OCPP endpoint
- `/ocpp/1.6/{chargePointId}` — version-prefixed alternate path

`{chargePointId}` uniquely identifies each charge point and must match the `chargePointId`
configured under `devices.charger` in `config.yaml`.

## Configuration

```yaml
ocpp:
  enabled: true
  heartbeatInterval: 300            # seconds, returned in BootNotification
  connectionTimeout: 60
  callTimeoutSeconds: 30            # how long the server waits for a charger's reply
  acceptUnknownChargePoints: false  # auto-accept a charger on first connect
  acceptUnknownIdTags: true         # accept any idTag not on the allow-list
  autoProbeOnBoot: true             # probe SupportedFeatureProfiles on boot

database:
  path: ems.db                      # SQLite store for allow-list / idTags / settings / transactions
```

## Message Format

OCPP 1.6J uses a JSON array framing over WebSocket:

```jsonc
[2, "unique-id", "Action", { … }]                 // CALL (request)
[3, "unique-id", { … }]                            // CALLRESULT (response)
[4, "unique-id", "ErrorCode", "Description", {}]   // CALLERROR
```

## Supported Actions

### Charge Point → Central System (inbound, handled in `OcppService`)
- **BootNotification** — registration; returns `heartbeatInterval`
- **Heartbeat** — keep-alive
- **Authorize** — idTag authorization (against the allow-list / `acceptUnknownIdTags`)
- **StartTransaction** / **StopTransaction** — charging-session lifecycle, persisted to SQLite
- **StatusNotification** — connector status updates
- **MeterValues** — live power; the EMS reads `Power.Active.Import`
- **DataTransfer** — vendor-specific data (accepted)

### Central System → Charge Point (outbound commands)
- **SetChargingProfile** / **ClearChargingProfile** — throttle / release charging current
- **GetConfiguration** — used to probe `SupportedFeatureProfiles` (SmartCharging detection)
- **RemoteStartTransaction** / **RemoteStopTransaction** — start/stop a session remotely
- **Reset** — reboot the charge point (Soft/Hard)
- **TriggerMessage** — ask the charger to send a specific message (e.g. MeterValues)

## Capability Detection

OCPP support varies by charger, so the server detects two capabilities at runtime:

- **SmartCharging** — probed via `GetConfiguration("SupportedFeatureProfiles")` on boot
  (`autoProbeOnBoot`). Required before the EMS can issue `SetChargingProfile`; without it,
  throttling is a logged no-op.
- **Power reading** — set once the charger reports a `Power.Active.Import` measurand in
  `MeterValues`.

Both surface as pills on `/ocpp-ui`. When a charger lacks SmartCharging, run the Webasto
Modbus hybrid setup described in the [operator guide](docs/adding-an-ocpp-charger.md#5-capability-detection-and-the-webasto-fallback).

## Persistence

State lives in SQLite (Exposed) at `database.path`:

| Table | Contents |
|-------|----------|
| `ocpp_charge_points` | Allow-list / status of every charge point seen |
| `ocpp_id_tags` | Authorized RFID idTags |
| `ocpp_charger_control` | Per-charger control settings (Max A, EMS-auto) |
| `ocpp_transactions` | Completed charging sessions |

A subtlety on restart: if the server (re)starts mid-charge, `StartTransaction` is not replayed,
so the transaction counter is bumped past any charger-side ids recovered from `MeterValues` to
avoid issuing duplicate transaction ids.

## Testing

Tests live in [`src/test/kotlin/ocpp/`](src/test/kotlin/ocpp/) (package `io.konektis.ocpp`):

- `OcppServerTest` — endpoint handshake, subprotocol, message routing
- `OcppServiceTest` — inbound handlers, session state, allow-list / idTag behavior
- `OcppCommandsTest` — outbound commands (SetChargingProfile, RemoteStart/Stop, Reset, …)
- `OcppCorrelationTest` — request/response id correlation and timeouts
- `OcppCapabilityTest` — SmartCharging / power-read capability detection
- `OcppWebUiTest` — the `/ocpp-ui` REST API and live status feed

Run them:

```bash
./gradlew test --tests "io.konektis.ocpp.*"
```

## Standards Compliance

- OCPP 1.6 JSON Specification (Edition 2, 2019)
- Core Profile
- Smart Charging Profile (SetChargingProfile / ClearChargingProfile)
- Remote Trigger Profile (TriggerMessage)

## Scope

- The OCPP endpoint and `/ocpp-ui` are **LAN-only** (no TLS/auth on `/ocpp`; `/ocpp-ui` uses the
  shared HTTP Basic credentials). The deployment trusts the local network.
- OCPP 2.0.1 is not supported.
- Power is read from `Power.Active.Import`; deriving it from cumulative
  `Energy.Active.Import.Register` is not implemented.
- Long-term power history lives separately in ClickHouse — see
  [docs/adding-clickhouse-history.md](docs/adding-clickhouse-history.md).
</content>
