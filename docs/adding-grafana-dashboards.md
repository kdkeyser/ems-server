# Grafana dashboards (LAN-only)

Grafana OSS runs in the NAS docker-compose stack and reads the ClickHouse power-history
tables (`ems.power_raw`, `ems.power_1m`). It is **read-only** — it cannot modify device
state or control anything — and is **LAN-only** (not exposed on the Cloudflare tunnel).

The `grafana` service is already declared in `docker-compose.yml`. No changes to the
compose file are needed; just set two environment variables and start the service.

## Enabling it

Add two variables to the `.env` file on the NAS alongside the existing `TUNNEL_TOKEN`:

```bash
GRAFANA_ADMIN_PASSWORD=<choose a strong password>
GRAFANA_CH_PASSWORD=<choose a strong password>
```

`GRAFANA_ADMIN_PASSWORD` is the password for the Grafana `admin` account.
`GRAFANA_CH_PASSWORD` is the password for the read-only ClickHouse user (`grafana`)
that Grafana uses to query the `ems` database. Both are required — compose refuses to
start the service if either is unset.

Then bring up both services (ClickHouse must be running first):

```bash
docker compose up -d clickhouse grafana
```

## Reaching it

Open `http://<nas-ip>:3000` on the LAN. Log in as `admin` with the password you set in
`GRAFANA_ADMIN_PASSWORD`. Sign-up and anonymous access are disabled.

## Dashboards

Three dashboards are provisioned automatically from `deploy/grafana/dashboards/`:

| Dashboard | Contents |
|-----------|----------|
| **Power Flow** | Live power lines (grid, solar, charger, heat pump, battery) + battery SoC |
| **Energy Balance** | Daily kWh totals: grid import/export, solar produced, charger, heat pump |
| **Battery & Devices** | Battery power and SoC over time + daily charge/discharge throughput |

## Ad-hoc exploration

Grafana's **Explore** mode lets you run queries against the `EMS ClickHouse` datasource
using the visual query builder or raw SQL. Because the datasource user is read-only,
exploration is safe — you cannot modify or delete any data.

## Dashboards-as-code

The committed files in `deploy/grafana/dashboards/*.json` are the source of truth. The
provisioning configuration allows UI updates, so you can edit panels directly in Grafana.
To persist a change:

1. Edit the panel in the Grafana UI.
2. **Share → Export → Save to file** (download the dashboard JSON).
3. Replace the corresponding file in `deploy/grafana/dashboards/` and commit it.

On the next `docker compose up -d grafana` the updated JSON is loaded automatically.

## Verify it works

1. On the NAS: confirm `GRAFANA_ADMIN_PASSWORD` and `GRAFANA_CH_PASSWORD` are set in
   `.env`, then run `docker compose up -d clickhouse grafana`.
2. Open `http://<nas-ip>:3000` and log in as `admin`.
3. Go to **Connections → Data sources → EMS ClickHouse → Save & test** — expect
   "Data source is working".
4. Go to **Dashboards → Power Flow** — expect live power lines once the EMS has written
   data to ClickHouse (requires `clickhouse.enabled: true` in `config.yaml`; see
   [docs/adding-clickhouse-history.md](adding-clickhouse-history.md)).
5. Go to **Explore**, pick **EMS ClickHouse**, run `SELECT count() FROM ems.power_raw`
   — expect a number greater than zero.

## Troubleshooting

- **Panels show "code: 164, Cannot modify 'max_execution_time' setting in readonly mode"** —
  the `grafana` ClickHouse user must use a `readonly=2` profile, not the stock `readonly`
  (=1) profile. The datasource sets `max_execution_time` per query, which `readonly=1`
  forbids. `deploy/clickhouse/users.d/grafana.xml` defines a `grafana_readonly` profile
  (`<readonly>2</readonly>`) for this; `readonly=2` still blocks all data/DDL changes.
- **Panels show no data but ClickHouse has rows** — confirm the EMS writer is actually
  inserting (`SELECT count(), max(ts) FROM ems.power_raw`) and that the dashboard time
  range covers when data started arriving.

## Security note

Grafana is intentionally **not** added to the Cloudflare tunnel. The tunnel stays scoped
to the `/ws` and `/status-ws` WebSocket endpoints. Grafana is accessible on the LAN only.
