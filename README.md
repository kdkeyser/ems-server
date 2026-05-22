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
