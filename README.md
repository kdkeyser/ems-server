# EMS Server

A locally-running home energy management system that optimises power distribution
between solar panels, a home battery, a car charger, and a heat pump in real time.
It streams live power data to the companion Android app, persists history for charts
and Grafana dashboards, and can optionally display the connected car's charge level.

## Hardware

| Device | Model | Protocol |
|--------|-------|----------|
| Smart grid meter | HomeWizard P1 | HTTP GET `/api/v1/data` |
| Solar inverters (×2) | SMA Sunny Boy | Modbus TCP |
| Home battery | SMA Sunny Boy Storage | Modbus TCP |
| Car charger | Webasto Unite **or** any OCPP 1.6J charge point | Modbus TCP / WebSocket |
| Heat pump | Daikin Altherma 3 (HomeHub) | Modbus TCP |
| Car (optional, read-only) | BMW via CarData MQTT stream | MQTT |

## Prerequisites

- JDK 17+
- Gradle (wrapper included)
- All Modbus/HTTP devices reachable on the local network (see `config.yaml` for IPs)

## Configuration

Edit `src/main/resources/config.yaml` (or mount an external file in a container — see
[Deployment](#deployment)). Key blocks:

```yaml
ocpp:                       # OCPP charge-point server (see docs/adding-an-ocpp-charger.md)
  enabled: true
  heartbeatInterval: 300
  connectionTimeout: 60

database:
  path: ems.db              # SQLite: OCPP allow-list, idTags, transactions, CarData token

clickhouse:
  enabled: false            # Power-history store (see docs/adding-clickhouse-history.md)

websocket:
  username: user            # credentials for /ws, /history, and /ocpp-ui
  password: password        # ⚠ change this — the server warns at startup if left default

grid:
  type: P1HomeWizard
  host: 192.168.x.x

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
    # Modbus charger:
    - type: WebastoUnite
      name: Webasto Unite
      host: 192.168.x.x
      chargingCurrent:
        min: 6.0            # minimum amps before charger stops instead of running inefficiently
        max: 32.0
    # …or an OCPP charge point (it dials in to /ocpp/{chargePointId}):
    # - type: OCPP
    #   name: Garage
    #   chargePointId: CP01
    #   chargingCurrent: { min: 6.0, max: 32.0 }
  heatPump:
    - type: DaikinHomeHub
      name: Daikin Altherma 3
      host: 192.168.x.x
  car: []                   # optional BMW CarData (see docs/adding-bmw-cardata.md)
```

## Build & Run

```bash
./gradlew build     # compile and run tests
./gradlew run       # start the server on port 8080
./gradlew shadowJar # produce a fat jar in build/libs/
```

## API

| Endpoint | Auth | Description |
|----------|------|-------------|
| `WS /ws` | message-based | Live power data stream. Send `{"type":"Authenticate","username":"…","password":"…"}` first, then receive `PowerUsageUpdate`, mode, charger, and `CarStateUpdate` messages. |
| `GET /status` · `WS /status-ws` | none | Human-readable server/device status page and its live feed. |
| `GET /history` | HTTP Basic | Power history (ClickHouse). Params `range` (`1h`…`365d`) and `resolution` (`5s`/`1m`). Returns `503` when ClickHouse is disabled. See [docs/adding-clickhouse-history.md](docs/adding-clickhouse-history.md). |
| `GET /ocpp-ui` · `/ocpp-ui/api/*` | HTTP Basic | OCPP management web page: charge-point allow-list, idTags, transactions, charger control, start/stop/reset. |
| `WS /ocpp/{id}` · `WS /ocpp/1.6/{id}` | OCPP subprotocol | OCPP 1.6J endpoints that charge points dial into (subprotocol `ocpp1.6`). |

HTTP Basic and the `/ws` authenticate message both use the `websocket.username` /
`websocket.password` credentials.

## Optimisation Strategy

The default `SurplusPriorityStrategy` allocates surplus solar power in this order:

1. **Heat pump** — unrestricted when surplus available; throttled on deficit
2. **Car charger** — receives remaining surplus, clamped to configured `[min, max]` amps
3. **Battery** — charges with any remaining surplus; discharges to cover deficit

## OCPP charger support

The server is itself an OCPP 1.6J central system: a charge point dials in over WebSocket
to `/ocpp/{chargePointId}`, the EMS reads its power from `MeterValues` and throttles it via
`SetChargingProfile` (Smart Charging profile required). A web page at `/ocpp-ui` manages the
charge-point allow-list, RFID idTags, transactions, and manual control. State (allow-list,
idTags, settings, transactions) is persisted in SQLite at `database.path`.

See [docs/adding-an-ocpp-charger.md](docs/adding-an-ocpp-charger.md) for connecting and
configuring a charge point.

## Power history (ClickHouse)

When `clickhouse.enabled: true`, every 5-second tick is written to ClickHouse (grid, solar,
charger, heat-pump, battery power and battery charge %). The `GET /history` API serves it to
the app's charts at `5s` or `1m` resolution, with TTL'd raw rows and indefinitely-retained
1-minute aggregates. See [docs/adding-clickhouse-history.md](docs/adding-clickhouse-history.md).

## Grafana dashboards

LAN-only Grafana OSS reads the ClickHouse history and ships three provisioned dashboards
(Power Flow, Energy Balance, Battery & Devices). It is read-only and intentionally **not**
exposed on the remote tunnel. See [docs/adding-grafana-dashboards.md](docs/adding-grafana-dashboards.md).

## Car charge level (BMW CarData)

Optionally, the app's main screen shows the BMW's battery percentage, streamed live from
**BMW CarData** over MQTT. This is **read-only** — the EMS only displays the value, it never
controls the car (AC charging has no State-of-Charge channel, so it comes from the car's own
cloud). Disabled by default; enable per-vehicle under `devices.car`. See
[docs/adding-bmw-cardata.md](docs/adding-bmw-cardata.md).

## Remote access

The EMS is reachable from outside the LAN at `wss://ems.kenas.be` via a Cloudflare Tunnel,
scoped to the `/ws` and `/status-ws` WebSocket endpoints, with edge authentication on top of
the in-app credentials. See [docs/remote-access-getting-started.md](docs/remote-access-getting-started.md)
and [docs/remote-access-cloudflare-runbook.md](docs/remote-access-cloudflare-runbook.md).

## Deployment

The server runs as a container on the NAS via `docker-compose.yml` (EMS + optional ClickHouse
+ Grafana + Cloudflare tunnel). In a container the config is mounted at `/config/config.yaml`
(override with the `EMS_CONFIG` env var) so device IPs, credentials, and the DB path can change
without rebuilding the image. See `deploy/` and `deploy_to_nas.sh`.

## Architecture

See [docs/architecture.md](docs/architecture.md) for the data-flow diagram and component
overview, and [CLAUDE.md](CLAUDE.md) for the project layout, device protocol details, and
hardware quirks.
</content>
</invoke>
