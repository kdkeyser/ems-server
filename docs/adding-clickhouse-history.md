# Power-history store (ClickHouse)

The EMS persists every 5-second tick to ClickHouse, enabling history charts and energy
totals in the app. Six power fields are stored per tick: grid, solar, charger, heat pump,
and battery power (Watts), plus battery charge (%). Sign convention is the same as the
rest of the EMS — **negative = producing/exporting, positive = consuming/importing**.

This integration is **read-only from the EMS's perspective** — it writes power data and
answers `GET /history` queries; it never controls any device. It is **disabled by
default** and requires no extra hardware beyond the ClickHouse container.

## Enabling it

The `clickhouse` service is already declared in `docker-compose.yml`. It starts
automatically when you run `docker compose up -d` — no changes to the compose file are
needed.

Set the `clickhouse` block in `deploy/config.yaml` (the template already has this with
`enabled: true`):

```yaml
# Power-history store. Reached over the shared Docker bridge as http://clickhouse:8123.
clickhouse:
  enabled: true
  host: clickhouse
  port: 8123
  database: ems
  # user: default      # optional; defaults to the `default` user
  # password: ""        # optional; set only if `default` (or your user) has a password
```

When `enabled: false` (the default in `src/main/resources/config.yaml`) the writer is
dormant and `GET /history` returns `503`.

### Authentication

The writer and the `GET /history` query path both authenticate to ClickHouse over HTTP
Basic, using `clickhouse.user` / `clickhouse.password` (default: `default` with an empty
password). The bundled `docker-compose.yml` mounts
`deploy/clickhouse/users.d/default.xml`, which pins the `default` user to **passwordless,
bridge-reachable** so the writer connects without credentials — recent ClickHouse images
otherwise auto-generate a random `default` password, which makes every insert fail with
`AUTHENTICATION_FAILED` (516). If you set a password on `default` (or point at a dedicated
user), set `clickhouse.password` (and `clickhouse.user`) to match.

## Schema and retention

`deploy/clickhouse-init.sql` is mounted into `/docker-entrypoint-initdb.d/` and applied
automatically on the first ClickHouse start. It creates:

| Table | Engine | Retention |
|-------|--------|-----------|
| `ems.power_raw` | MergeTree | Raw 5-second rows, TTL 1 year then deleted |
| `ems.power_1m` | AggregatingMergeTree | 1-minute averages, retained indefinitely |

A materialized view (`ems.power_1m_mv`) feeds `power_1m` automatically as new rows
arrive in `power_raw` — no manual intervention needed on a fresh deploy.

## The `GET /history` API

Authenticate with HTTP Basic using the same `websocket.username` and
`websocket.password` credentials as the `/ws` endpoint.

### Query parameters

| Parameter | Values | Default |
|-----------|--------|---------|
| `range` | `1h`, `6h`, `24h`, `7d`, `30d`, `365d` | `1h` |
| `resolution` | `5s`, `1m` | auto: `5s` for ≤ 24h, `1m` for longer |

### Response

```json
{
  "resolution": "5s",
  "points": [
    {
      "ts": 1749340800,
      "gridPower": 420,
      "solarPower": -1850,
      "chargerPower": 3680,
      "heatpumpPower": 0,
      "batteryPower": -250,
      "batteryCharge": 78
    }
  ]
}
```

All power values are in Watts. `batteryCharge` is a percentage (0–100). `null` means the
device reading was unavailable at that tick.

### Status codes

| Code | Meaning |
|------|---------|
| `200` | OK — JSON body as above |
| `400` | Invalid `range` or `resolution` parameter |
| `503` | ClickHouse disabled in config |
| `502` | ClickHouse unreachable or query error |

### Example request

```bash
curl -u admin:yourpassword \
  "https://ems.kenas.be/history?range=6h&resolution=1m"
```

## Developer note: running the integration test

`HistoryIntegrationTest` uses Testcontainers to spin up a real ClickHouse instance. It
requires a container socket. With rootless **podman**, enable it once:

```bash
systemctl --user enable --now podman.socket
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
```

Then `./gradlew test` picks it up. Without a socket the test self-skips — it does not
fail the build.

## Migration note: backfilling `power_1m` from existing data

The materialized view only aggregates new inserts. If you are migrating an instance that
already has data in `power_raw`, run the one-time backfill query that is included
(commented out) at the bottom of `deploy/clickhouse-init.sql`:

```sql
INSERT INTO ems.power_1m
SELECT toStartOfMinute(ts), avgState(grid_power), avgState(solar_power), avgState(charger_power),
       avgState(heatpump_power), avgState(battery_power), avgState(battery_charge)
FROM ems.power_raw GROUP BY toStartOfMinute(ts);
```

On a fresh deploy this is a no-op and can be ignored.
