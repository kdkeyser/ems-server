# Grafana Dashboards for ClickHouse Power History — Design

**Date:** 2026-06-09
**Status:** Approved

## Overview

Add a browser-based dashboard layer over the ClickHouse power-history store, served from the NAS for a single technical user on the LAN. It provides a curated set of standard dashboards (power flow, energy totals, per-device detail) **and** fluid ad-hoc exploration — both a point-and-click query builder and raw ClickHouse SQL — via **Grafana**. Everything is provisioned as code in the repo, matching how the rest of this project is deployed (compose, `config.yaml`, `clickhouse-init.sql`).

This is **read-only**: Grafana only reads `ems.power_raw` / `ems.power_1m`; it never writes power data or controls any device.

## Tool Choice

**Grafana OSS** with the official first-party `grafana-clickhouse-datasource` plugin. Chosen over Metabase (GUI-first; dashboards-as-code is awkward, ClickHouse driver is community-maintained) and a custom server UI (reinvents exploration for no benefit). Grafana uniquely delivers both curated dashboards-as-code and a query editor that does visual building *and* raw SQL, plus Explore mode for throwaway questions — the "both" requirement — with a native ClickHouse integration.

## Architecture & Deployment

A `grafana` service joins the existing NAS `docker-compose.yml` on the same bridge network, so it reaches ClickHouse internally. No new ClickHouse ports are exposed.

```yaml
  grafana:
    image: grafana/grafana-oss:11.4.0
    container_name: grafana
    restart: unless-stopped
    ports:
      - "3000:3000"            # LAN-only dashboard UI
    environment:
      GF_INSTALL_PLUGINS: grafana-clickhouse-datasource 4.5.1
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?set in .env}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
    volumes:
      - ./deploy/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./deploy/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - clickhouse
```

Provisioning lives under `deploy/grafana/`, all committed:

```
deploy/grafana/
├── provisioning/
│   ├── datasources/clickhouse.yaml     # the ClickHouse datasource
│   └── dashboards/provider.yaml        # loads dashboards/*.json
└── dashboards/
    ├── power-flow.json
    ├── energy-balance.json
    └── battery-devices.json
```

- The plugin is installed at container start (pinned version `4.5.1`); the NAS has outbound internet, as it already pulls `cloudflared` and `clickhouse`.
- `grafana-data` persists Grafana's own DB (login, sessions, UI experiments).
- Grafana is pulled from its registry like `clickhouse`/`cloudflared` — **not** baked into the Podman tarball, so `deploy/build-podman.sh` is unchanged.

The `.env` on the NAS gains `GRAFANA_ADMIN_PASSWORD` and `GRAFANA_CH_PASSWORD` (alongside the existing `TUNNEL_TOKEN`).

## ClickHouse Read-Only Access

Grafana is for exploration — ad-hoc SQL where a stray `ALTER`/`DROP` would be bad — so it connects as a dedicated **read-only** `grafana` user, not the writer's full-access default.

The user is defined via a ClickHouse `users.d` XML drop-in (not inline `init.sql`, because the entrypoint runs `init.sql` as plain SQL with no env substitution — whereas ClickHouse config XML supports `from_env`, so no secret is committed):

`deploy/clickhouse/users.d/grafana.xml`:
```xml
<clickhouse>
    <users>
        <grafana>
            <password from_env="GRAFANA_CH_PASSWORD"/>
            <networks><ip>::/0</ip></networks>
            <profile>readonly</profile>          <!-- built-in: readonly=1 -->
            <quota>default</quota>
            <allow_databases><database>ems</database></allow_databases>
        </grafana>
    </users>
</clickhouse>
```

Mounted into the `clickhouse` service, and the env var passed to that container:
```yaml
  clickhouse:
    environment:
      GRAFANA_CH_PASSWORD: ${GRAFANA_CH_PASSWORD:?set in .env}
    volumes:
      - ./deploy/clickhouse/users.d/grafana.xml:/etc/clickhouse-server/users.d/grafana.xml:ro
      # (existing volumes unchanged)
```

The datasource (`provisioning/datasources/clickhouse.yaml`) connects as `grafana` over the native protocol, with the password from the env var (Grafana provisioning supports `$ENV` interpolation):

```yaml
apiVersion: 1
datasources:
  - name: EMS ClickHouse
    type: grafana-clickhouse-datasource
    isDefault: true
    jsonData:
      host: clickhouse
      port: 9000
      protocol: native
      defaultDatabase: ems
      username: grafana
    secureJsonData:
      password: $GRAFANA_CH_PASSWORD
```

The env var is also passed to the `grafana` container. **No secrets are committed.** ClickHouse remains unexposed outside the bridge; this is defense-in-depth. The existing EMS writer keeps using the default user, unchanged.

## Standard Dashboards

Three provisioned dashboards, each with Grafana's time-range picker. Sign convention throughout: **negative = producing/exporting, positive = consuming/importing** (as stored).

### 1. Power Flow (Overview) — `power-flow.json`
The "what's happening" view.
- Time-series of grid / solar / charger / heat-pump / battery power (W) on one graph, signed.
- Battery SoC (%) as a second panel (or right-hand axis).
- Stat panels: current grid, current solar, current battery SoC.
- Reads `power_raw` for short ranges and `power_1m` (via `avgMerge`) for long ranges.

### 2. Energy Balance (Totals) — `energy-balance.json`
Daily/period energy in kWh — directly-measured quantities only (no self-consumption % / autarky; explicitly out of scope).
- Bars per day: solar produced, grid imported, grid exported, battery charged, battery discharged.
- Per-device energy per day: charger (EV) kWh, heat-pump kWh.
- Stat panels: totals over the selected period for each of the above.

### 3. Battery & Devices — `battery-devices.json`
Per-device detail.
- Battery: SoC over time, charge/discharge power, kWh throughput per day.
- Charger (EV): power over time, energy delivered per day.
- Heat pump: power over time, energy per day, rough runtime (minutes with power above a small threshold).

## Analytical SQL: power → energy

Converting stored power samples to energy is the error-prone core, so it is pinned down and **tested** (see Testing). Energy is power integrated over time. Daily/period aggregations read `power_1m` (1-minute averages), which is an `AggregatingMergeTree` — queries **must** `avgMerge` the aggregate columns. Each minute's energy is `avg_watts / 60 / 1000` kWh.

Representative query (daily grid import/export, kWh):
```sql
SELECT
    toDate(ts) AS day,
    sumIf(g, g > 0) / 60.0 / 1000.0 AS grid_imported_kwh,
    sumIf(-g, g < 0) / 60.0 / 1000.0 AS grid_exported_kwh
FROM (
    SELECT ts, avgMerge(grid_power) AS g
    FROM ems.power_1m
    GROUP BY ts
)
GROUP BY day
ORDER BY day
```

The same sign-split pattern yields the other channels:
- **solar produced** = `sumIf(-s, s < 0)/60/1000` (solar is negative when producing)
- **battery charged** = `sumIf(b, b > 0)/60/1000`; **discharged** = `sumIf(-b, b < 0)/60/1000`
- **charger / heat-pump energy** = `sum(c)/60/1000` (loads are non-negative)

Short-range time-series panels read `power_raw` directly (raw signed Watts, no `avgMerge`). The `/60/1000` factor is specific to the 1-minute table; `power_raw` energy uses the 5-second interval (`× 5/3600/1000`). These constants and the sign splits are exactly what the tests verify.

## Auth & Exposure

- **LAN-only.** Only Grafana's `3000` is published on the NAS; ClickHouse stays internal (no published ports). Reachable at `http://<nas-ip>:3000`.
- **Login required.** Grafana's built-in admin login; password from `.env` (`GRAFANA_ADMIN_PASSWORD`). Sign-up and anonymous access disabled.
- **Not on the Cloudflare tunnel.** Ingress stays scoped to `/ws` + `/status-ws` on `ems-server`; Grafana is never routed to the edge, so it is not reachable from the internet. (Remote access later would be a one-line ingress addition behind Cloudflare Access — out of scope now.)

## Dashboards-as-Code Workflow

The committed `deploy/grafana/dashboards/*.json` are the source of truth. The provider (`provider.yaml`) is set with `allowUiUpdates: true` (tweak panels and write ad-hoc queries freely in the browser) and `disableDeletion: true` (a stray UI delete can't drop a provisioned dashboard). To persist a change, export the dashboard JSON (Share → Export, or the HTTP API) and commit it. Explore mode and throwaway queries need nothing committed.

## Testing

Testing targets the two things that actually break; a full Grafana end-to-end (render panels) is **out of scope** — high effort, low value for a personal tool. Manual smoke test: open `:3000`, dashboards load with data.

1. **Analytical SQL** (the real bug source). The kWh-integration and per-device energy queries are kept as standalone `.sql` files under `deploy/grafana/queries/` and tested against a **seeded ClickHouse** (Testcontainers, reusing the rootless-podman setup and the existing `src/test/kotlin/history/` Testcontainers harness from the history feature). Fixtures with known inputs (e.g. constant 1000 W solar for one hour → 1.0 kWh) assert each query returns the expected kWh, exercising the sign splits and interval-scaling constants. The dashboard JSON embeds these verified queries.
2. **Provisioning validity.** A lightweight test asserts every `datasources/*.yaml`, `dashboards/provider.yaml`, and `dashboards/*.json` parses (YAML/JSON well-formed) and references the datasource by its exact name (`EMS ClickHouse`). Cheap guard against typos that would silently blank a panel.

## New / Changed Files

```
deploy/grafana/
├── provisioning/datasources/clickhouse.yaml
├── provisioning/dashboards/provider.yaml
├── dashboards/power-flow.json
├── dashboards/energy-balance.json
├── dashboards/battery-devices.json
└── queries/*.sql                          # tested analytical queries
deploy/clickhouse/users.d/grafana.xml      # read-only CH user
docs/adding-grafana-dashboards.md          # operator guide
```

Modified: `docker-compose.yml` (grafana service + clickhouse env var/volume). The two new env vars (`GRAFANA_ADMIN_PASSWORD`, `GRAFANA_CH_PASSWORD`) are NAS `.env` values guarded by `${VAR:?}` in compose and documented in the operator guide — they do **not** touch the EMS `config.yaml`. `CLAUDE.md` gains a pointer. Tests live in `src/test/kotlin/` (reusing the history Testcontainers harness) for the SQL checks, plus a small provisioning-validity test.

## Out of Scope

- Self-consumption % / autarky %.
- Cost/tariff calculations.
- Remote (internet) access to Grafana.
- Multi-user / household read access.
- Grafana end-to-end render testing.
