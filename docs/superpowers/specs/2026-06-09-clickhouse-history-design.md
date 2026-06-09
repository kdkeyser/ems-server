# ClickHouse Historical Data Collector — Design

**Date:** 2026-06-09
**Status:** Approved

## Overview

Store every EMS tick in ClickHouse so the Android app can display power-flow charts (last hour to last year) and daily/monthly energy totals. Raw 5-second data is retained for 1 year; a materialized view automatically aggregates to 1-minute resolution for longer-range queries. History is exposed via a new authenticated REST endpoint on the existing Ktor server.

## ClickHouse Schema

Database: `ems`. Two tables.

**`power_raw`** — one row per EMS tick (5-second cadence), retained for 1 year:

```sql
CREATE DATABASE IF NOT EXISTS ems;

CREATE TABLE ems.power_raw (
    ts             DateTime,
    grid_power     Nullable(Int32),
    solar_power    Nullable(Int32),
    charger_power  Nullable(Int32),
    heatpump_power Nullable(Int32),
    battery_power  Nullable(Int32),
    battery_charge Nullable(Int32)
) ENGINE = MergeTree()
ORDER BY ts
TTL ts + INTERVAL 1 YEAR DELETE;
```

**`power_1m`** — 1-minute partial aggregates, merged automatically by `AggregatingMergeTree`, retained indefinitely:

```sql
CREATE TABLE ems.power_1m (
    ts             DateTime,
    grid_power     AggregateFunction(avg, Nullable(Int32)),
    solar_power    AggregateFunction(avg, Nullable(Int32)),
    charger_power  AggregateFunction(avg, Nullable(Int32)),
    heatpump_power AggregateFunction(avg, Nullable(Int32)),
    battery_power  AggregateFunction(avg, Nullable(Int32)),
    battery_charge AggregateFunction(avg, Nullable(Int32))
) ENGINE = AggregatingMergeTree()
ORDER BY ts;

CREATE MATERIALIZED VIEW ems.power_1m_mv
TO ems.power_1m AS
SELECT
    toStartOfMinute(ts)      AS ts,
    avgState(grid_power)     AS grid_power,
    avgState(solar_power)    AS solar_power,
    avgState(charger_power)  AS charger_power,
    avgState(heatpump_power) AS heatpump_power,
    avgState(battery_power)  AS battery_power,
    avgState(battery_charge) AS battery_charge
FROM ems.power_raw
GROUP BY ts;
```

`AggregatingMergeTree` is required (not plain `MergeTree`) because the writer flushes every 30 seconds — each flush triggers the materialized view and writes a partial aggregate row for the current minute. `AggregatingMergeTree` merges those partials correctly using `-State`/`-Merge` combinators. With plain `MergeTree` + `avg()`, each minute would accumulate ~2 duplicate rows that are never reconciled.

Queries against `power_1m` must use `avgMerge()`:
```sql
SELECT ts, avgMerge(grid_power), avgMerge(solar_power), ...
FROM ems.power_1m GROUP BY ts ORDER BY ts
```

`ts` is the wall-clock time the tick was produced, captured client-side in `EnergyManager.tick()` (see Write Path) and sent explicitly in each `INSERT` — never `now()` on the ClickHouse side, since rows are buffered up to 30s before insert. Timestamps are UTC; range filters in the query path use UTC consistently.

All power values are Watts (Int32). `Nullable` handles missing device readings. The schema lives in `deploy/clickhouse-init.sql`, mounted as `/docker-entrypoint-initdb.d/init.sql` so ClickHouse applies it automatically on first start. The materialized view only captures new inserts; on migration from an existing `power_raw`, run the one-time backfill included as a commented command in `clickhouse-init.sql` (using `-State` functions to match the `AggregatingMergeTree` format). On a fresh deploy this is a no-op.

The schema is designed to accommodate future 1-second resolution without structural changes — only the TTL and insert cadence would change.

## Write Path — `HistoryWriter`

New class in `src/main/kotlin/history/HistoryWriter.kt`. DI singleton, started from `Application.kt` as a background coroutine alongside `EnergyManager` and `CarDataService`.

### Tick source — a non-conflating tap, not the StateFlow

`EnergyManager.emsStateFlow` is a `StateFlow`, which **conflates and drops values equal to the previous one**. During quiet periods (e.g. overnight: solar 0, charger 0, steady grid load, slowly-moving SoC) two consecutive ticks can produce an identical `EMSState`, so the second would never be emitted — leaving silent gaps in history. The live WebSocket is fine with this; a history collector that promises "one row per 5s tick" is not.

To guarantee every tick reaches the writer, `EnergyManager` gains a dedicated tap. In `tick()`, right after `emsStateFlow.value = emsState`, it also emits onto a `MutableSharedFlow<TimestampedEmsState>` with `extraBufferCapacity` and `onBufferOverflow = DROP_OLDEST`:

```kotlin
// EnergyManager
val emsHistoryFlow = MutableSharedFlow<TimestampedEmsState>(
    extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
)
// inside tick(), after emsStateFlow.value = emsState:
emsHistoryFlow.tryEmit(TimestampedEmsState(Instant.now(), emsState))
```

`TimestampedEmsState(ts: Instant, state: EMSState)` lives in the `history` package. **The timestamp is captured here, at tick time** — not at insert time — because rows are buffered up to 30s before they are written. `EMSState` itself carries no timestamp, so the tap stamps it. The SoC/power values are unchanged; only the time of capture is added.

### Ingestion — a bounded `Channel`, not a shared mutable buffer

The writer does not share a mutable buffer between two coroutines (that would be a data race on a non-thread-safe collection). Instead it owns a bounded `Channel<TimestampedEmsState>` (capacity = `MAX_BUFFER`, `onBufferOverflow = DROP_OLDEST`). One coroutine collects `emsHistoryFlow` and `trySend`s into the channel; the flush coroutine drains it.

```
EnergyManager.emsHistoryFlow ──collect──► Channel (bounded, DROP_OLDEST)
                                              └─ 30s flush: drain ──► HTTP POST ──► ClickHouse
```

- **Flush:** every 30 seconds, the flush coroutine drains all currently-queued items via a `tryReceive` loop into a local list, then POSTs a single `INSERT INTO ems.power_raw FORMAT Values (...)` to `http://<host>:<port>/`.
- **Failure / no data loss on retry:** the drained list is held locally and the channel items are only considered consumed once the POST returns 2xx. On failure, the writer logs a WARN, **prepends the un-sent rows back into the next flush batch** (kept in a pending-list field, capped at `MAX_BUFFER`), and retries on the next 30-second cycle. Rows are never dropped because of a *transient* POST failure — only via channel/pending-list overflow during a sustained outage.
- **Buffer cap:** `MAX_BUFFER = 10_000` rows (~14 hours of ticks at 5s). On overflow (sustained ClickHouse outage longer than that), oldest rows are dropped (`DROP_OLDEST`) and a single WARN is logged. Each row is tiny, so this cap is cheap. The EMS loop is never blocked — `tryEmit`/`trySend` are non-suspending and never apply backpressure to `tick()`.
- **HTTP client:** `HistoryWriter` owns a dedicated `HttpClient(CIO)` instance (separate from the device-polling client) with timeouts appropriate for bulk inserts (e.g. 10s connect, 30s request).
- **Disabled state:** if `clickhouse.enabled = false` (default), `HistoryWriter.start()` is a no-op and `EnergyManager` still emits onto `emsHistoryFlow` harmlessly (no collector).

## Query Path — `HistoryRepository` + REST Endpoint

**`HistoryRepository`** (`src/main/kotlin/history/HistoryRepository.kt`) owns all ClickHouse queries. It builds a SQL `SELECT` from the requested range and resolution, fires it at `http://<host>:<port>/?query=<url-encoded-sql>&default_format=JSONEachRow`, and parses the response into `List<PowerPoint>`. ClickHouse returns `JSONEachRow` as newline-delimited JSON objects (one per line), not a JSON array — the parser splits on newlines and decodes each line independently. Fields are mapped by name (ClickHouse column name → Kotlin property), not by position; this avoids transposition bugs since `EMSState` contains `gridVoltage` between `gridPower` and `chargerPower` but that field is not stored.

**Endpoint:** `GET /history`, protected by Ktor's `authenticate("auth-basic")` block (same as other HTTP endpoints). As a prerequisite, `configureSecurity()` must be updated to accept `WebSocketConfig` and validate against `wsConfig.username/password` — currently it has hardcoded `"user"/"password"`. This fix is in-scope for this feature. Note this also changes the credentials accepted on any other `auth-basic`-guarded route (e.g. `/users`), unifying them with the WebSocket credentials. The Android app reaches `/history` with an `Authorization: Basic` header carrying the same `wsConfig` credentials it already uses for `/ws` (app-side change, tracked separately).

Query parameters:

| Param | Values | Default |
|---|---|---|
| `range` | `1h`, `6h`, `24h`, `7d`, `30d`, `365d` | `1h` |
| `resolution` | `5s`, `1m` | auto |

Auto-resolution: `5s` (→ `power_raw`) for ranges ≤ 24h; `1m` (→ `power_1m`) for longer ranges. The resolved resolution is echoed back in the response.

**Response:**
```json
{
  "resolution": "5s",
  "points": [
    {
      "ts": 1749456000,
      "gridPower": -1200,
      "solarPower": -3400,
      "chargerPower": 1100,
      "heatpumpPower": 800,
      "batteryPower": -1300,
      "batteryCharge": 72
    }
  ]
}
```

Power sign convention matches the rest of the EMS: negative = producing/exporting, positive = consuming/importing.

Invalid `range` or `resolution` values (anything outside the enumerated sets above) are rejected with **400** — the handler validates each param against its allowed set before building any SQL. The set is fixed and enum-mapped, so no user string is ever interpolated into the query.

**Error responses:**
- 400 with JSON error body for an unknown `range` or `resolution`
- 503 if `clickhouse.enabled = false`
- 502 with JSON error body if ClickHouse is unreachable or returns an error

## Config

New optional top-level block in `Config.kt`. Config classes are loaded by Hoplite — no `@Serializable` annotation needed or wanted:

```kotlin
data class ClickHouseConfig(
    val enabled: Boolean = false,
    val host: String = "clickhouse",
    val port: Int = 8123,
    val database: String = "ems",
)

data class Config(
    ...
    val clickhouse: ClickHouseConfig = ClickHouseConfig(),
)
```

`config.yaml.template` gets the block pre-filled with `enabled: true` (it only appears when ClickHouse is in the compose). Existing configs need no changes.

## Deployment

`docker-compose.yml` gains a `clickhouse` service on the shared bridge network. No ports are exposed externally — `ems-server` reaches it as `http://clickhouse:8123`.

```yaml
clickhouse:
  image: clickhouse/clickhouse-server:24.8
  container_name: clickhouse
  restart: unless-stopped
  volumes:
    - clickhouse-data:/var/lib/clickhouse
    - ./deploy/clickhouse-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
  ulimits:
    nofile: { soft: 262144, hard: 262144 }

volumes:
  clickhouse-data:
```

The EMS server starts and connects to ClickHouse lazily (first flush attempt) — no hard startup dependency. If ClickHouse is slow to start, the buffer absorbs the first few minutes of ticks.

## Error Handling

| Scenario | Behaviour |
|---|---|
| ClickHouse unreachable at flush | WARN log, un-sent rows re-queued into next batch, retry in 30s |
| Sustained outage > `MAX_BUFFER` (10k rows ≈ 14h) | Oldest rows dropped (DROP_OLDEST), single WARN logged |
| Invalid range/resolution param on GET /history | 400 with JSON error body |
| ClickHouse query error on GET /history | 502 with JSON error body |
| `clickhouse.enabled = false` | HistoryWriter is no-op; GET /history returns 503 |

## Testing

**Unit tests:**
- `HistoryWriterTest` — MockK `HttpClient`; verifies flush batching, channel-overflow (DROP_OLDEST) behaviour, **re-queue of un-sent rows on a failed POST** (no data loss on transient failure), no-op when disabled, and that the captured client-side timestamp (not insert time) ends up in the INSERT payload.
- `HistoryRepositoryTest` — MockK `HttpClient`; verifies SQL generation for each range/resolution combination, `avgMerge`+`GROUP BY ts` shape for the `power_1m` path, newline-delimited JSONEachRow parsing, 400 on invalid params, 502 on ClickHouse error.

**Integration test:**
- `HistoryIntegrationTest` — Testcontainers (`org.testcontainers:clickhouse`) spins up a real ClickHouse instance, applies `deploy/clickhouse-init.sql`, inserts rows via `HistoryWriter`, queries via `HistoryRepository`, and asserts returned points match inserted data. Covers: raw table round-trip, 1-minute aggregate (`avgMerge`) correctness, and TTL config presence.
- **Container-runtime gating:** this project builds and deploys with **podman**, and Testcontainers needs a Docker-API socket (`DOCKER_HOST`, e.g. the podman socket). To keep `./gradlew build` green on a machine without a usable container runtime, the integration test is `@EnabledIf` a `dockerAvailable()` probe (`assumeTrue` short-circuit) so it is skipped — not failed — when no socket is reachable. It still runs in environments that have one. (Alternatively a separate `integrationTest` source set/task; the gate is the simpler choice given the single test.)

New test dependency: `org.testcontainers:clickhouse:1.20.1`.

## New Files

```
src/main/kotlin/history/
├── HistoryWriter.kt          # collects emsHistoryFlow → bounded Channel → batched flush
├── HistoryRepository.kt      # SQL builder + ClickHouse HTTP queries
├── HistoryRoutes.kt          # GET /history Ktor route
└── TimestampedEmsState.kt    # (ts: Instant, state: EMSState) — tick-time capture

src/test/kotlin/history/
├── HistoryWriterTest.kt
├── HistoryRepositoryTest.kt
└── HistoryIntegrationTest.kt   # docker-gated (assumeTrue)

deploy/
└── clickhouse-init.sql       # schema: power_raw, power_1m, materialized view
```

Modified files: `Config.kt`, `EnergyManager.kt` (add non-conflating `emsHistoryFlow` tap in `tick()`), `AppModule.kt`, `Application.kt`, `Security.kt` (wire wsConfig into Basic auth), `docker-compose.yml`, `deploy/config.yaml.template`, `build.gradle.kts`, `gradle/libs.versions.toml`.
