# ClickHouse Historical Data Collector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every 5-second EMS tick into ClickHouse and expose it via `GET /history` so the Android app can render power-flow charts (1h…1y) and energy totals.

**Architecture:** `EnergyManager.tick()` emits each tick onto a non-conflating `MutableSharedFlow<TimestampedEmsState>`. A `HistoryWriter` consumes that flow into a bounded `Channel`, batches rows, and flushes them every 30s to ClickHouse over its own HTTP client (re-queuing on failure). A `HistoryRepository` answers `GET /history` by querying `power_raw` (raw 5s) or `power_1m` (1-minute aggregate). ClickHouse runs as an extra Docker Compose service; the schema is applied by an init SQL file.

**Tech Stack:** Kotlin 2.2, Ktor 3.4.2 (server + CIO client), kotlinx-serialization, kotlin-inject DI, Hoplite config, ClickHouse 24.8 (HTTP interface), Testcontainers for the integration test. Tests use kotlin-test-junit + MockK + Ktor `MockEngine`/`testApplication`.

**Spec:** `docs/superpowers/specs/2026-06-09-clickhouse-history-design.md`

---

## File Structure

**New files:**
- `src/main/kotlin/history/TimestampedEmsState.kt` — `(ts: Instant, state: EMSState)` capture type
- `src/main/kotlin/history/HistorySql.kt` — pure helpers: enums `HistoryRange`/`HistoryResolution`, resolution selection, INSERT-VALUES row formatting, SELECT builder, JSONEachRow parsing, `PowerPoint`/`HistoryResponse` DTOs
- `src/main/kotlin/history/HistoryWriter.kt` — channel ingestion + 30s batched flush
- `src/main/kotlin/history/HistoryRepository.kt` — HTTP query against ClickHouse
- `src/main/kotlin/history/HistoryRoutes.kt` — `GET /history` Ktor route
- `src/main/kotlin/config/ClickHouseConfig.kt` — config data class
- `deploy/clickhouse-init.sql` — schema (power_raw, power_1m, materialized view)
- Test files mirroring the above under `src/test/kotlin/history/`

**Modified files:**
- `src/main/kotlin/config/Config.kt` — add `clickhouse` field
- `src/main/kotlin/ems/EnergyManager.kt` — add `emsHistoryFlow` tap in `tick()`
- `src/main/kotlin/Security.kt` — validate against `WebSocketConfig` creds
- `src/main/kotlin/di/AppModule.kt`, `di/AppComponent.kt` — provide writer + repository
- `src/main/kotlin/Application.kt` — start writer, register route, pass wsConfig to security
- `docker-compose.yml`, `deploy/config.yaml.template`, `src/main/resources/config.yaml`
- `build.gradle.kts`, `gradle/libs.versions.toml` — Testcontainers test deps

**Design note — why pure helpers in `HistorySql.kt`:** this codebase tests HTTP-bearing classes by extracting the logic into top-level pure functions (see `cardata/SocParser.kt`, `cardata/CarDataAuth.kt`'s `parseTokenResponse`) and unit-testing those, keeping the IO classes thin. We follow that pattern: all SQL strings and parsing live in pure functions; `HistoryWriter`/`HistoryRepository` only do IO.

---

## Task 1: ClickHouseConfig + Config wiring

**Files:**
- Create: `src/main/kotlin/config/ClickHouseConfig.kt`
- Modify: `src/main/kotlin/config/Config.kt`
- Test: `src/test/kotlin/config/ConfigTest.kt` (add cases)

- [ ] **Step 1: Write the failing test**

Add these two tests to the existing `ConfigTest` class in `src/test/kotlin/config/ConfigTest.kt`:

```kotlin
    @Test
    fun clickHouseDefaultsDisabled() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertEquals(false, config.clickhouse.enabled)
        assertEquals("clickhouse", config.clickhouse.host)
        assertEquals(8123, config.clickhouse.port)
        assertEquals("ems", config.clickhouse.database)
    }

    @Test
    fun clickHouseLoadsFromFile() {
        val yaml = """
            grid:
              type: P1HomeWizard
              gridType: Phase3_400V
              host: 10.9.9.9
            devices:
              charger:
                - type: OCPP
                  name: CP
                  chargePointId: CP01
                  chargingCurrent: { min: 6.0, max: 32.0 }
            ocpp:
              enabled: true
              heartbeatInterval: 300
              connectionTimeout: 60
            clickhouse:
              enabled: true
              host: ch-host
              port: 9000
              database: hist
        """.trimIndent()
        val tmp = File.createTempFile("ems-config-ch", ".yaml")
        tmp.writeText(yaml); tmp.deleteOnExit()
        val config = loadConfig("/config.yaml", filePath = tmp.absolutePath)
        assertEquals(true, config.clickhouse.enabled)
        assertEquals("ch-host", config.clickhouse.host)
        assertEquals(9000, config.clickhouse.port)
        assertEquals("hist", config.clickhouse.database)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: FAIL — `config.clickhouse` unresolved reference (compile error).

- [ ] **Step 3: Create the config class**

Create `src/main/kotlin/config/ClickHouseConfig.kt`. Note: Hoplite binds by constructor — **no `@Serializable`** (config classes in this project are plain data classes):

```kotlin
package io.konektis.config

/**
 * ClickHouse history store config. Optional and disabled by default so existing deployments load
 * unchanged. [host]/[port] address the ClickHouse HTTP interface (8123); on the NAS it is reached
 * as http://clickhouse:8123 over the shared Docker bridge.
 */
data class ClickHouseConfig(
    val enabled: Boolean = false,
    val host: String = "clickhouse",
    val port: Int = 8123,
    val database: String = "ems",
)
```

- [ ] **Step 4: Add the field to Config**

In `src/main/kotlin/config/Config.kt`, add the `clickhouse` field to the `Config` data class (after `database`):

```kotlin
@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val database: DatabaseConfig = DatabaseConfig(),
    val clickhouse: ClickHouseConfig = ClickHouseConfig(),
    val refreshThreads : Int = 50
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: PASS (all ConfigTest cases).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/config/ClickHouseConfig.kt src/main/kotlin/config/Config.kt src/test/kotlin/config/ConfigTest.kt
git commit -m "feat(history): add ClickHouseConfig (disabled by default)"
```

---

## Task 2: Pure DTOs, enums, and resolution selection

**Files:**
- Create: `src/main/kotlin/history/HistorySql.kt`
- Test: `src/test/kotlin/history/HistorySqlTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/history/HistorySqlTest.kt`:

```kotlin
package io.konektis.history

import kotlin.test.*

class HistorySqlTest {
    @Test fun rangeParsesKnownValues() {
        assertEquals(HistoryRange.H1, HistoryRange.fromParam("1h"))
        assertEquals(HistoryRange.D365, HistoryRange.fromParam("365d"))
    }

    @Test fun rangeReturnsNullForUnknown() {
        assertNull(HistoryRange.fromParam("99h"))
        assertNull(HistoryRange.fromParam(""))
        assertNull(HistoryRange.fromParam(null))
    }

    @Test fun resolutionParsesKnownValues() {
        assertEquals(HistoryResolution.RAW, HistoryResolution.fromParam("5s"))
        assertEquals(HistoryResolution.MINUTE, HistoryResolution.fromParam("1m"))
    }

    @Test fun resolutionNullForUnknown() {
        assertNull(HistoryResolution.fromParam("2m"))
    }

    @Test fun autoResolutionIsRawUpTo24hMinuteBeyond() {
        assertEquals(HistoryResolution.RAW, autoResolution(HistoryRange.H1))
        assertEquals(HistoryResolution.RAW, autoResolution(HistoryRange.H24))
        assertEquals(HistoryResolution.MINUTE, autoResolution(HistoryRange.D7))
        assertEquals(HistoryResolution.MINUTE, autoResolution(HistoryRange.D365))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: FAIL — unresolved references (compile error).

- [ ] **Step 3: Write the enums, DTOs, and resolution logic**

Create `src/main/kotlin/history/HistorySql.kt`:

```kotlin
package io.konektis.history

import kotlinx.serialization.Serializable

/** Time window for a history query. [seconds] is how far back from now to read. */
enum class HistoryRange(val param: String, val seconds: Long) {
    H1("1h", 3_600), H6("6h", 21_600), H24("24h", 86_400),
    D7("7d", 604_800), D30("30d", 2_592_000), D365("365d", 31_536_000);

    companion object {
        fun fromParam(p: String?): HistoryRange? = entries.firstOrNull { it.param == p }
    }
}

/** Which table to read: RAW = power_raw (5s), MINUTE = power_1m (avgMerge). */
enum class HistoryResolution(val param: String) {
    RAW("5s"), MINUTE("1m");

    companion object {
        fun fromParam(p: String?): HistoryResolution? = entries.firstOrNull { it.param == p }
    }
}

/** Default resolution when the caller does not pin one: raw up to 24h, 1-minute beyond. */
fun autoResolution(range: HistoryRange): HistoryResolution =
    if (range.seconds <= HistoryRange.H24.seconds) HistoryResolution.RAW else HistoryResolution.MINUTE

@Serializable
data class PowerPoint(
    val ts: Long,
    val gridPower: Int?,
    val solarPower: Int?,
    val chargerPower: Int?,
    val heatpumpPower: Int?,
    val batteryPower: Int?,
    val batteryCharge: Int?,
)

@Serializable
data class HistoryResponse(
    val resolution: String,
    val points: List<PowerPoint>,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/history/HistorySql.kt src/test/kotlin/history/HistorySqlTest.kt
git commit -m "feat(history): range/resolution enums + auto-resolution + DTOs"
```

---

## Task 3: SELECT SQL builder

**Files:**
- Modify: `src/main/kotlin/history/HistorySql.kt`
- Test: `src/test/kotlin/history/HistorySqlTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HistorySqlTest`:

```kotlin
    @Test fun rawSelectReadsPowerRawWithWindow() {
        val sql = buildSelectSql("ems", HistoryRange.H1, HistoryResolution.RAW)
        assertTrue(sql.contains("FROM ems.power_raw"), sql)
        assertTrue(sql.contains("ts >= now() - INTERVAL 3600 SECOND"), sql)
        assertTrue(sql.contains("toUnixTimestamp(ts) AS ts"), sql)
        assertTrue(sql.contains("ORDER BY ts"), sql)
        assertFalse(sql.contains("avgMerge"), sql)
        assertTrue(sql.endsWith("FORMAT JSONEachRow"), sql)
    }

    @Test fun minuteSelectUsesAvgMergeAndGroupBy() {
        val sql = buildSelectSql("ems", HistoryRange.D365, HistoryResolution.MINUTE)
        assertTrue(sql.contains("FROM ems.power_1m"), sql)
        assertTrue(sql.contains("avgMerge(grid_power)"), sql)
        assertTrue(sql.contains("GROUP BY ts"), sql)
        assertTrue(sql.contains("ts >= now() - INTERVAL 31536000 SECOND"), sql)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: FAIL — `buildSelectSql` unresolved.

- [ ] **Step 3: Implement the builder**

Add to `src/main/kotlin/history/HistorySql.kt`. The column list is fixed (never user input), so string-building is injection-safe; the only variables are the enum-derived table, the integer window, and the trusted database name from config:

```kotlin
private val POWER_COLUMNS = listOf(
    "grid_power", "solar_power", "charger_power", "heatpump_power", "battery_power", "battery_charge",
)

/**
 * Build the ClickHouse SELECT for a history query. RAW reads power_raw directly; MINUTE reads the
 * AggregatingMergeTree power_1m and must avgMerge + GROUP BY ts to collapse partial-aggregate rows.
 * Only enum-derived and config-trusted values are interpolated — no user strings.
 */
fun buildSelectSql(database: String, range: HistoryRange, resolution: HistoryResolution): String {
    val window = "ts >= now() - INTERVAL ${range.seconds} SECOND"
    return when (resolution) {
        HistoryResolution.RAW -> buildString {
            append("SELECT toUnixTimestamp(ts) AS ts, ")
            append(POWER_COLUMNS.joinToString(", "))
            append(" FROM $database.power_raw WHERE $window ORDER BY ts FORMAT JSONEachRow")
        }
        HistoryResolution.MINUTE -> buildString {
            append("SELECT toUnixTimestamp(ts) AS ts, ")
            append(POWER_COLUMNS.joinToString(", ") { "avgMerge($it) AS $it" })
            append(" FROM $database.power_1m WHERE $window GROUP BY ts ORDER BY ts FORMAT JSONEachRow")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/history/HistorySql.kt src/test/kotlin/history/HistorySqlTest.kt
git commit -m "feat(history): SELECT SQL builder for raw + 1-minute queries"
```

---

## Task 4: JSONEachRow response parser

**Files:**
- Modify: `src/main/kotlin/history/HistorySql.kt`
- Test: `src/test/kotlin/history/HistorySqlTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HistorySqlTest`:

```kotlin
    @Test fun parsesNewlineDelimitedRows() {
        val body = """
            {"ts":1749456000,"grid_power":-1200,"solar_power":-3400,"charger_power":1100,"heatpump_power":800,"battery_power":-1300,"battery_charge":72}
            {"ts":1749456005,"grid_power":-1100,"solar_power":-3300,"charger_power":1100,"heatpump_power":810,"battery_power":-1200,"battery_charge":72}
        """.trimIndent()
        val points = parsePowerPoints(body)
        assertEquals(2, points.size)
        assertEquals(1749456000L, points[0].ts)
        assertEquals(-1200, points[0].gridPower)
        assertEquals(72, points[1].batteryCharge)
    }

    @Test fun parseHandlesNullsAndBlankLines() {
        val body = "{\"ts\":1749456000,\"grid_power\":null,\"solar_power\":0,\"charger_power\":0,\"heatpump_power\":0,\"battery_power\":0,\"battery_charge\":50}\n\n"
        val points = parsePowerPoints(body)
        assertEquals(1, points.size)
        assertNull(points[0].gridPower)
        assertEquals(0, points[0].solarPower)
    }

    @Test fun parseEmptyBodyReturnsEmpty() {
        assertTrue(parsePowerPoints("").isEmpty())
        assertTrue(parsePowerPoints("   \n  ").isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: FAIL — `parsePowerPoints` unresolved.

- [ ] **Step 3: Implement the parser**

Add to `src/main/kotlin/history/HistorySql.kt`. ClickHouse `JSONEachRow` returns one JSON object per line (not a JSON array); parse line-by-line and map by field name. The `power_1m` path returns `avgMerge` Float64 values, so read numerically and round:

```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

private val historyJson = Json { ignoreUnknownKeys = true }

private fun intField(obj: kotlinx.serialization.json.JsonObject, name: String): Int? {
    val el = obj[name] ?: return null
    val prim = el.jsonPrimitive
    if (prim.content == "null") return null
    return prim.doubleOrNull?.roundToInt()
}

/** Parse a ClickHouse JSONEachRow body (newline-delimited objects) into PowerPoints; tolerant of
 *  blank lines. Float64 averages from power_1m are rounded to whole Watts. */
fun parsePowerPoints(body: String): List<PowerPoint> =
    body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val o = historyJson.parseToJsonElement(line).jsonObject
            PowerPoint(
                ts = o["ts"]!!.jsonPrimitive.content.toDouble().toLong(),
                gridPower = intField(o, "grid_power"),
                solarPower = intField(o, "solar_power"),
                chargerPower = intField(o, "charger_power"),
                heatpumpPower = intField(o, "heatpump_power"),
                batteryPower = intField(o, "battery_power"),
                batteryCharge = intField(o, "battery_charge"),
            )
        }
        .toList()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/history/HistorySql.kt src/test/kotlin/history/HistorySqlTest.kt
git commit -m "feat(history): tolerant JSONEachRow → PowerPoint parser"
```

---

## Task 5: TimestampedEmsState + INSERT-VALUES formatter

**Files:**
- Create: `src/main/kotlin/history/TimestampedEmsState.kt`
- Modify: `src/main/kotlin/history/HistorySql.kt`
- Test: `src/test/kotlin/history/HistorySqlTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HistorySqlTest`:

```kotlin
    @Test fun insertValuesFormatsTimestampAndNulls() {
        val rows = listOf(
            TimestampedEmsState(
                java.time.Instant.ofEpochSecond(1749456000),
                io.konektis.ems.EMSState(
                    gridPower = -1200, gridVoltage = 230, chargerPower = 1100,
                    heatpumpPower = 800, solarPower = -3400, batteryPower = -1300,
                    batteryCharge = 72,
                ),
            ),
            TimestampedEmsState(
                java.time.Instant.ofEpochSecond(1749456005),
                io.konektis.ems.EMSState(
                    gridPower = null, gridVoltage = null, chargerPower = null,
                    heatpumpPower = null, solarPower = null, batteryPower = null,
                    batteryCharge = null,
                ),
            ),
        )
        val sql = buildInsertSql("ems", rows)
        assertTrue(sql.startsWith("INSERT INTO ems.power_raw"), sql)
        assertTrue(sql.contains("FORMAT Values"), sql)
        // First row: epoch wrapped in toDateTime, columns in schema order, NULL spelled out.
        assertTrue(sql.contains("(toDateTime(1749456000),-1200,-3400,1100,800,-1300,72)"), sql)
        assertTrue(sql.contains("(toDateTime(1749456005),NULL,NULL,NULL,NULL,NULL,NULL)"), sql)
    }

    @Test fun insertValuesEmptyRowsIsBlank() {
        assertTrue(buildInsertSql("ems", emptyList()).isEmpty())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: FAIL — `TimestampedEmsState` / `buildInsertSql` unresolved.

- [ ] **Step 3: Create TimestampedEmsState**

Create `src/main/kotlin/history/TimestampedEmsState.kt`:

```kotlin
package io.konektis.history

import io.konektis.ems.EMSState
import java.time.Instant

/**
 * An EMS tick stamped with the wall-clock time it was produced. The timestamp is captured at tick
 * time (in EnergyManager) — not at insert time — because HistoryWriter buffers rows up to 30s before
 * writing them. EMSState carries no time field of its own.
 */
data class TimestampedEmsState(val ts: Instant, val state: EMSState)
```

- [ ] **Step 4: Implement the INSERT builder**

Add to `src/main/kotlin/history/HistorySql.kt`. Columns are written in the schema order (grid, solar, charger, heatpump, battery, battery_charge) — **by name, not by EMSState constructor position** (EMSState orders charger before solar and includes gridVoltage, which we do not store):

```kotlin
private fun intOrNull(v: Int?): String = v?.toString() ?: "NULL"

/**
 * Build a single `INSERT INTO <db>.power_raw ... FORMAT Values` statement for [rows], or "" if empty.
 * Timestamps are sent explicitly (toDateTime(epochSeconds)); ClickHouse stores DateTime as UTC.
 * Values are mapped by schema column name, not EMSState field order.
 */
fun buildInsertSql(database: String, rows: List<TimestampedEmsState>): String {
    if (rows.isEmpty()) return ""
    val tuples = rows.joinToString(",") { row ->
        val s = row.state
        "(toDateTime(${row.ts.epochSecond})," +
            "${intOrNull(s.gridPower)},${intOrNull(s.solarPower)},${intOrNull(s.chargerPower)}," +
            "${intOrNull(s.heatpumpPower)},${intOrNull(s.batteryPower)},${intOrNull(s.batteryCharge)})"
    }
    return "INSERT INTO $database.power_raw " +
        "(ts,grid_power,solar_power,charger_power,heatpump_power,battery_power,battery_charge) " +
        "FORMAT Values $tuples"
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistorySqlTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/history/TimestampedEmsState.kt src/main/kotlin/history/HistorySql.kt src/test/kotlin/history/HistorySqlTest.kt
git commit -m "feat(history): TimestampedEmsState + INSERT-VALUES formatter"
```

---

## Task 6: EnergyManager non-conflating tap

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Test: `src/test/kotlin/ems/EnergyManagerHistoryTapTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/ems/EnergyManagerHistoryTapTest.kt`. It drives two `tick()`s producing the **same** `EMSState` and asserts both reach `emsHistoryFlow` (proving it does not conflate like the StateFlow would):

```kotlin
package io.konektis.ems

import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.charger.Charger
import io.konektis.devices.grid.Grid
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.ocpp.db.ChargerControlStore
import io.konektis.history.TimestampedEmsState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EnergyManagerHistoryTapTest {
    private fun emptyWorld(): World {
        val grid = mockk<Grid>()
        coEvery { grid.getState() } returns null
        return mockk<World> {
            every { this@mockk.grid } returns grid
            every { solar } returns emptyMap<String, Solar>()
            every { batteries } returns emptyMap<String, Battery>()
            every { chargers } returns emptyMap<String, Charger>()
            every { smartConsumers } returns emptyMap<String, SmartConsumer>()
        }
    }

    @Test
    fun tickEmitsEveryTickEvenWhenStateUnchanged() = runTest {
        val config = mockk<Config>(relaxed = true)
        val strategy = mockk<Strategy>(relaxed = true)
        val store = mockk<ChargerControlStore>(relaxed = true)
        val em = EnergyManager(emptyWorld(), config, strategy, store, null)

        val seen = mutableListOf<TimestampedEmsState>()
        val collector = launch { em.emsHistoryFlow.collect { seen.add(it) } }

        em.tick()   // empty world -> identical EMSState each time
        em.tick()
        // give the collector a chance to run on the test dispatcher
        kotlinx.coroutines.yield()

        assertEquals(2, seen.size, "both identical ticks must reach the history flow")
        collector.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerHistoryTapTest"`
Expected: FAIL — `emsHistoryFlow` unresolved.

- [ ] **Step 3: Add the tap to EnergyManager**

In `src/main/kotlin/ems/EnergyManager.kt`:

Add imports near the existing flow imports (top of file):
```kotlin
import io.konektis.history.TimestampedEmsState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
```

Add the flow as a property next to `emsStateFlow` (around line 39):
```kotlin
    /**
     * Non-conflating tap of every tick for the history collector. Unlike [emsStateFlow] (a StateFlow
     * that drops values equal to the previous one), this emits on every tick so identical consecutive
     * states during quiet periods are still recorded. DROP_OLDEST keeps tick() non-blocking if no
     * collector is attached or it falls behind.
     */
    private val _emsHistoryFlow = MutableSharedFlow<TimestampedEmsState>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val emsHistoryFlow: SharedFlow<TimestampedEmsState> = _emsHistoryFlow.asSharedFlow()
```

In `tick()`, immediately after the existing `emsStateFlow.value = emsState` line, add:
```kotlin
        _emsHistoryFlow.tryEmit(TimestampedEmsState(Instant.now(), emsState))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerHistoryTapTest"`
Expected: PASS.

- [ ] **Step 5: Run the full ems test package to check nothing regressed**

Run: `./gradlew test --tests "io.konektis.ems.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt src/test/kotlin/ems/EnergyManagerHistoryTapTest.kt
git commit -m "feat(history): non-conflating emsHistoryFlow tap in EnergyManager.tick()"
```

---

## Task 7: HistoryWriter (channel ingestion + batched flush)

**Files:**
- Create: `src/main/kotlin/history/HistoryWriter.kt`
- Test: `src/test/kotlin/history/HistoryWriterTest.kt`

`HistoryWriter` exposes a testable seam: a suspend `flushOnce()` that drains the channel and POSTs, separate from the timing loop. Tests drive `flushOnce()` directly using a Ktor `MockEngine` HttpClient. The class accepts its `HttpClient` as a constructor parameter so tests inject a `MockEngine` one.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/history/HistoryWriterTest.kt`:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.konektis.ems.EMSState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.*

class HistoryWriterTest {
    private fun state() = EMSState(
        gridPower = -100, gridVoltage = 230, chargerPower = 0, heatpumpPower = 0,
        solarPower = -500, batteryPower = 0, batteryCharge = 50,
    )
    private fun row(epoch: Long) = TimestampedEmsState(Instant.ofEpochSecond(epoch), state())

    private fun mockClient(
        captured: MutableList<String>, status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        val engine = MockEngine { req ->
            captured.add(req.body.toString().let { _ ->
                // body is a TextContent; read its text
                (req.body as io.ktor.http.content.TextContent).text
            })
            if (status.isSuccess()) respond("") else respondError(status)
        }
        return HttpClient(engine)
    }

    @Test
    fun flushPostsBufferedRowsAsSingleInsert() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = true), mockClient(captured))
        writer.enqueue(row(1749456000))
        writer.enqueue(row(1749456005))

        writer.flushOnce()

        assertEquals(1, captured.size, "one POST for the whole batch")
        assertTrue(captured[0].contains("INSERT INTO ems.power_raw"))
        assertTrue(captured[0].contains("toDateTime(1749456000)"))
        assertTrue(captured[0].contains("toDateTime(1749456005)"))
    }

    @Test
    fun flushWithNoRowsDoesNotPost() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = true), mockClient(captured))
        writer.flushOnce()
        assertEquals(0, captured.size)
    }

    @Test
    fun failedPostRequeuesRowsForNextFlush() = runTest {
        val captured = mutableListOf<String>()
        // First flush: server errors. Rows must survive into the second flush.
        val writer = HistoryWriter(
            ClickHouseConfig(enabled = true),
            mockClient(captured, status = HttpStatusCode.ServiceUnavailable),
        )
        writer.enqueue(row(1749456000))
        writer.flushOnce()  // fails, re-queues
        assertEquals(1, captured.size, "attempted once")

        // Swap to a client that succeeds and flush again: the row must be retried.
        val captured2 = mutableListOf<String>()
        writer.swapClientForTest(mockClient(captured2))
        writer.flushOnce()
        assertEquals(1, captured2.size)
        assertTrue(captured2[0].contains("toDateTime(1749456000)"), "re-queued row retried")
    }

    @Test
    fun disabledWriterIgnoresEnqueueAndFlush() = runTest {
        val captured = mutableListOf<String>()
        val writer = HistoryWriter(ClickHouseConfig(enabled = false), mockClient(captured))
        writer.enqueue(row(1749456000))
        writer.flushOnce()
        assertEquals(0, captured.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistoryWriterTest"`
Expected: FAIL — `HistoryWriter` unresolved. (You also need the MockEngine test dependency; if the import fails to resolve, add it now: in `build.gradle.kts` add `testImplementation("io.ktor:ktor-client-mock:3.4.2")` and re-run.)

- [ ] **Step 3: Add the MockEngine test dependency**

In `gradle/libs.versions.toml` under `[libraries]`:
```toml
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```
In `build.gradle.kts`, add to the test dependencies block (with the other `testImplementation` lines):
```kotlin
    testImplementation(libs.ktor.client.mock)
```

- [ ] **Step 4: Implement HistoryWriter**

Create `src/main/kotlin/history/HistoryWriter.kt`:

```kotlin
package io.konektis.history

import io.klogging.Klogging
import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/**
 * Persists EMS ticks to ClickHouse. Ticks arrive via [enqueue] (fed from EnergyManager.emsHistoryFlow)
 * into a bounded channel; [flushOnce] drains the channel and any previously-failed rows and POSTs them
 * as one INSERT. A failed POST re-queues the batch so a transient ClickHouse blip loses no data; only a
 * sustained outage beyond [MAX_BUFFER] drops the oldest rows. Owns a dedicated HttpClient so its bulk
 * timeouts are isolated from device polling. No-op when ClickHouse is disabled.
 */
class HistoryWriter(
    private val config: ClickHouseConfig,
    httpClient: HttpClient,
) : Klogging {

    private var http: HttpClient = httpClient
    private val url = "http://${config.host}:${config.port}/"

    private val channel = Channel<TimestampedEmsState>(
        capacity = MAX_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Rows from a failed POST, retried at the head of the next flush. */
    private val pending = ArrayDeque<TimestampedEmsState>()

    /** Offer a tick to the buffer. Non-blocking; drops oldest on overflow. No-op when disabled. */
    fun enqueue(row: TimestampedEmsState) {
        if (!config.enabled) return
        channel.trySend(row)
    }

    /** Collect [flow] into the buffer, then flush every 30s, forever. Call from a launched coroutine. */
    suspend fun run(flow: SharedFlow<TimestampedEmsState>) {
        if (!config.enabled) {
            logger.info("ClickHouse history disabled; HistoryWriter not started")
            return
        }
        logger.info("HistoryWriter started -> $url")
        kotlinx.coroutines.coroutineScope {
            kotlinx.coroutines.launch { flow.collect { enqueue(it) } }
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushOnce()
            }
        }
    }

    /** Drain pending + channel and POST as one INSERT. On failure, re-queue (capped). */
    suspend fun flushOnce() {
        if (!config.enabled) return
        val batch = ArrayList<TimestampedEmsState>(pending)
        pending.clear()
        while (true) {
            val r = channel.tryReceive().getOrNull() ?: break
            batch.add(r)
        }
        if (batch.isEmpty()) return

        val sql = buildInsertSql(config.database, batch)
        val ok = runCatching {
            val resp: HttpResponse = http.post(url) { setBody(sql) }
            resp.status.isSuccess()
        }.getOrElse { e ->
            logger.warn(e, "ClickHouse insert failed: ${e.message}")
            false
        }
        if (!ok) {
            // Re-queue at the head for the next flush; trim oldest if we exceed the cap.
            pending.addAll(0, batch)
            while (pending.size > MAX_BUFFER) pending.removeFirst()
            logger.warn("Re-queued ${batch.size} history rows after failed flush (pending=${pending.size})")
        }
    }

    /** Test seam: replace the HTTP client between flushes. */
    fun swapClientForTest(client: HttpClient) { http = client }

    companion object {
        const val MAX_BUFFER = 10_000
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistoryWriterTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/main/kotlin/history/HistoryWriter.kt src/test/kotlin/history/HistoryWriterTest.kt
git commit -m "feat(history): HistoryWriter with channel batching + re-queue on failure"
```

---

## Task 8: HistoryRepository (HTTP query)

**Files:**
- Create: `src/main/kotlin/history/HistoryRepository.kt`
- Test: `src/test/kotlin/history/HistoryRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/history/HistoryRepositoryTest.kt`:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HistoryRepositoryTest {
    private fun repo(
        body: String, status: HttpStatusCode = HttpStatusCode.OK,
        capturedUrls: MutableList<String> = mutableListOf(),
    ): HistoryRepository {
        val engine = MockEngine { req ->
            capturedUrls.add(req.url.toString())
            respond(body, status)
        }
        return HistoryRepository(ClickHouseConfig(enabled = true), HttpClient(engine))
    }

    @Test
    fun queryReturnsParsedPointsAndEchoesResolution() = runTest {
        val body = """{"ts":1749456000,"grid_power":-1200,"solar_power":-3400,"charger_power":1100,"heatpump_power":800,"battery_power":-1300,"battery_charge":72}"""
        val result = repo(body).query(HistoryRange.H1, HistoryResolution.RAW)
        assertEquals("5s", result.resolution)
        assertEquals(1, result.points.size)
        assertEquals(-1200, result.points[0].gridPower)
    }

    @Test
    fun querySendsSqlAsQueryParam() = runTest {
        val urls = mutableListOf<String>()
        repo("", capturedUrls = urls).query(HistoryRange.D365, HistoryResolution.MINUTE)
        assertTrue(urls[0].contains("power_1m"), urls[0])
        assertTrue(urls[0].contains("avgMerge"), urls[0])
    }

    @Test
    fun queryThrowsOnClickHouseError() = runTest {
        assertFailsWith<HistoryQueryException> {
            repo("Code: 60. Unknown table", status = HttpStatusCode.InternalServerError)
                .query(HistoryRange.H1, HistoryResolution.RAW)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistoryRepositoryTest"`
Expected: FAIL — `HistoryRepository` / `HistoryQueryException` unresolved.

- [ ] **Step 3: Implement HistoryRepository**

Create `src/main/kotlin/history/HistoryRepository.kt`:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Thrown when ClickHouse is unreachable or returns a non-2xx response for a history query. */
class HistoryQueryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads power history from ClickHouse over its HTTP interface. SQL generation and parsing are the pure
 * helpers in HistorySql.kt; this class only does the HTTP call. Owns its HttpClient (injected).
 */
class HistoryRepository(
    private val config: ClickHouseConfig,
    private val http: HttpClient,
) {
    private val url = "http://${config.host}:${config.port}/"

    suspend fun query(range: HistoryRange, resolution: HistoryResolution): HistoryResponse {
        val sql = buildSelectSql(config.database, range, resolution)
        val resp: HttpResponse = try {
            http.get(url) { parameter("query", sql) }
        } catch (e: Exception) {
            throw HistoryQueryException("ClickHouse unreachable: ${e.message}", e)
        }
        if (!resp.status.isSuccess()) {
            throw HistoryQueryException("ClickHouse error ${resp.status}: ${resp.bodyAsText()}")
        }
        return HistoryResponse(resolution.param, parsePowerPoints(resp.bodyAsText()))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistoryRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/history/HistoryRepository.kt src/test/kotlin/history/HistoryRepositoryTest.kt
git commit -m "feat(history): HistoryRepository HTTP query against ClickHouse"
```

---

## Task 9: GET /history route

**Files:**
- Create: `src/main/kotlin/history/HistoryRoutes.kt`
- Test: `src/test/kotlin/history/HistoryRoutesTest.kt`

The route validates `range`/`resolution` against the enums (400 on bad input), returns 503 when disabled, 502 on `HistoryQueryException`, else 200 with `HistoryResponse` JSON. It is registered inside the existing `authenticate("auth-basic")` scope in production wiring (Task 11); the test installs the route without auth to focus on behaviour.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/history/HistoryRoutesTest.kt`:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.*

class HistoryRoutesTest {
    private fun repoWith(body: String) = HistoryRepository(
        ClickHouseConfig(enabled = true),
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }),
    )

    @Test
    fun returns200WithPoints() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHistory(ClickHouseConfig(enabled = true), repoWith(
                """{"ts":1749456000,"grid_power":-1200,"solar_power":0,"charger_power":0,"heatpump_power":0,"battery_power":0,"battery_charge":50}"""
            ))
        }
        val resp = client.get("/history?range=1h&resolution=5s")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"resolution\":\"5s\""))
        assertTrue(resp.bodyAsText().contains("-1200"))
    }

    @Test
    fun badRangeReturns400() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHistory(ClickHouseConfig(enabled = true), repoWith(""))
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/history?range=99h").status)
    }

    @Test
    fun badResolutionReturns400() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHistory(ClickHouseConfig(enabled = true), repoWith(""))
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/history?range=1h&resolution=2m").status)
    }

    @Test
    fun disabledReturns503() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHistory(ClickHouseConfig(enabled = false), repoWith(""))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/history?range=1h").status)
    }

    @Test
    fun defaultsRangeTo1hAndAutoResolution() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHistory(ClickHouseConfig(enabled = true), repoWith(""))
        }
        val resp = client.get("/history")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"resolution\":\"5s\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.history.HistoryRoutesTest"`
Expected: FAIL — `configureHistory` unresolved.

- [ ] **Step 3: Implement the route**

Create `src/main/kotlin/history/HistoryRoutes.kt`:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Registers GET /history. Validates range/resolution against the enums (400 on bad input), returns
 * 503 when ClickHouse is disabled and 502 when the query fails. In production this is mounted inside
 * the authenticate("auth-basic") scope (see Application wiring).
 */
fun Application.configureHistory(config: ClickHouseConfig, repository: HistoryRepository) {
    routing {
        get("/history") {
            if (!config.enabled) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "history disabled"))
                return@get
            }
            val rangeParam = call.request.queryParameters["range"] ?: "1h"
            val range = HistoryRange.fromParam(rangeParam) ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid range: $rangeParam"))
                return@get
            }
            val resParam = call.request.queryParameters["resolution"]
            val resolution = if (resParam == null) autoResolution(range)
                else HistoryResolution.fromParam(resParam) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid resolution: $resParam"))
                    return@get
                }
            try {
                call.respond(repository.query(range, resolution))
            } catch (e: HistoryQueryException) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "query failed")))
            }
        }
    }
}
```

Note: `call` is available via the route receiver; if the IDE flags it, add `import io.ktor.server.application.call` (Ktor 3.x). Verify against an existing route file (`Databases.kt`) for the exact import set used in this project.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.history.HistoryRoutesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/history/HistoryRoutes.kt src/test/kotlin/history/HistoryRoutesTest.kt
git commit -m "feat(history): GET /history route with validation + 503/502 handling"
```

---

## Task 10: Wire Security.kt to WebSocketConfig credentials

**Files:**
- Modify: `src/main/kotlin/Security.kt`
- Modify: `src/main/kotlin/Application.kt` (pass wsConfig to configureSecurity)
- Test: `src/test/kotlin/SecurityAuthTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/SecurityAuthTest.kt`. It mounts a protected route under `auth-basic` and checks the configured creds are accepted while the old hardcoded ones are rejected:

```kotlin
package io.konektis

import io.konektis.config.WebSocketConfig
import io.ktor.client.request.get
import io.ktor.client.request.basicAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.*

class SecurityAuthTest {
    private fun io.ktor.server.testing.ApplicationTestBuilder.appWith(cfg: WebSocketConfig) = application {
        configureSecurity(cfg)
        routing { authenticate("auth-basic") { get("/secret") { call.respondText("ok") } } }
    }

    @Test
    fun acceptsConfiguredCredentials() = testApplication {
        appWith(WebSocketConfig("alice", "s3cret"))
        val resp = client.get("/secret") { basicAuth("alice", "s3cret") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun rejectsWrongCredentials() = testApplication {
        appWith(WebSocketConfig("alice", "s3cret"))
        val resp = client.get("/secret") { basicAuth("user", "password") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.konektis.SecurityAuthTest"`
Expected: FAIL — `configureSecurity(cfg)` signature mismatch (currently takes no args).

- [ ] **Step 3: Update configureSecurity**

In `src/main/kotlin/Security.kt`, change the function to accept `WebSocketConfig` and validate against it. Add the import `import io.konektis.config.WebSocketConfig` and replace the hardcoded check:

```kotlin
fun Application.configureSecurity(wsConfig: WebSocketConfig) {
    install(Sessions) {
        cookie<MySession>("MY_SESSION") {
            cookie.extensions["SameSite"] = "lax"
        }
    }
    authentication {
        basic(name = "auth-basic") {
            realm = "Access to the '/' path"
            validate { credentials ->
                if (credentials.name == wsConfig.username && credentials.password == wsConfig.password) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
}
```

- [ ] **Step 4: Update the caller**

In `src/main/kotlin/Application.kt`, the `module(...)` function already receives `wsConfig: WebSocketConfig`. Change the `configureSecurity()` call to `configureSecurity(wsConfig)`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.konektis.SecurityAuthTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/Security.kt src/main/kotlin/Application.kt src/test/kotlin/SecurityAuthTest.kt
git commit -m "feat(history): auth-basic validates against configured websocket credentials"
```

---

## Task 11: DI wiring + Application startup + route mounting

**Files:**
- Modify: `src/main/kotlin/di/AppModule.kt`
- Modify: `src/main/kotlin/di/AppComponent.kt`
- Modify: `src/main/kotlin/Application.kt`

This task has no new unit test (it is composition); it is verified by `./gradlew build` compiling and the existing `ApplicationTest` still passing. The `/history` route is mounted inside `authenticate("auth-basic")`.

- [ ] **Step 1: Provide HistoryWriter and HistoryRepository in AppModule**

In `src/main/kotlin/di/AppModule.kt`, add imports:
```kotlin
import io.konektis.history.HistoryRepository
import io.konektis.history.HistoryWriter
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
```
Add two `@Provides` factories (each constructs a dedicated CIO HttpClient so timeouts are isolated from device polling):
```kotlin
    @ApplicationScope
    @Provides
    fun provideHistoryWriter(config: Config): HistoryWriter {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
        }
        return HistoryWriter(config.clickhouse, client)
    }

    @ApplicationScope
    @Provides
    fun provideHistoryRepository(config: Config): HistoryRepository {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
        }
        return HistoryRepository(config.clickhouse, client)
    }
```

- [ ] **Step 2: Expose them on AppComponent**

In `src/main/kotlin/di/AppComponent.kt`, add imports and abstract vals:
```kotlin
import io.konektis.history.HistoryRepository
import io.konektis.history.HistoryWriter
```
```kotlin
    abstract val historyWriter: HistoryWriter
    abstract val historyRepository: HistoryRepository
```

- [ ] **Step 3: Start the writer and mount the route in Application.kt**

In `src/main/kotlin/Application.kt`:

In the `coroutineScope { ... }` block (alongside `launch { component.carDataService.start() }`), add:
```kotlin
            launch { component.historyWriter.run(energyManager.emsHistoryFlow) }
```

Change the `module(...)` call to pass the history pieces. Update the `embeddedServer` `module(...)` invocation and the `module` function signature:

Call site (inside the `launch` that starts the server):
```kotlin
                    module(
                        energyManager, config.websocket, dataCollector.statusStateFlow,
                        component.ocppService, component.database,
                        component.historyRepository, config.clickhouse,
                    )
```

Function signature + body — add the two params and mount the route under auth. Add imports at the top of `Application.kt`:
```kotlin
import io.konektis.history.HistoryRepository
import io.konektis.history.configureHistory
import io.konektis.config.ClickHouseConfig
```
Update the function:
```kotlin
fun Application.module(
    energyManager: EnergyManager, wsConfig: WebSocketConfig, statusFlow: Flow<StatusState?>,
    ocppService: io.konektis.ocpp.OcppService, database: Database,
    historyRepository: HistoryRepository, clickhouse: ClickHouseConfig,
) {
    install(ContentNegotiation) { json() }
    configureSecurity(wsConfig)
    configureAdministration()
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer(ocppService)
    configureOcppWebUi(ocppService, energyManager)
    configureDatabases(database)
    configureHistoryAuthenticated(clickhouse, historyRepository)
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
```

- [ ] **Step 4: Refactor HistoryRoutes.kt to share the handler between authed and test mounts**

Replace the whole body of `src/main/kotlin/history/HistoryRoutes.kt` (created in Task 9) with the version below. The handler is extracted into `Route.historyRoute(...)`; `configureHistory` (test/dev, no auth) and `configureHistoryAuthenticated` (production, under `auth-basic`) both delegate to it. The handler logic is identical to Task 9 — just relocated:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The GET /history handler, mountable with or without auth. Validates range/resolution against the
 * enums (400 on bad input), returns 503 when ClickHouse is disabled and 502 when the query fails.
 */
fun Route.historyRoute(config: ClickHouseConfig, repository: HistoryRepository) {
    get("/history") {
        if (!config.enabled) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "history disabled"))
            return@get
        }
        val rangeParam = call.request.queryParameters["range"] ?: "1h"
        val range = HistoryRange.fromParam(rangeParam) ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid range: $rangeParam"))
            return@get
        }
        val resParam = call.request.queryParameters["resolution"]
        val resolution = if (resParam == null) autoResolution(range)
            else HistoryResolution.fromParam(resParam) ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid resolution: $resParam"))
                return@get
            }
        try {
            call.respond(repository.query(range, resolution))
        } catch (e: HistoryQueryException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "query failed")))
        }
    }
}

/** Test/dev mount: /history without auth. */
fun Application.configureHistory(config: ClickHouseConfig, repository: HistoryRepository) {
    routing { historyRoute(config, repository) }
}

/** Production mount: the same /history handler, behind auth-basic. */
fun Application.configureHistoryAuthenticated(config: ClickHouseConfig, repository: HistoryRepository) {
    routing { authenticate("auth-basic") { historyRoute(config, repository) } }
}
```

Re-run the Task 9 route test to confirm the refactor preserved behaviour:
Run: `./gradlew test --tests "io.konektis.history.HistoryRoutesTest"`
Expected: PASS (unchanged — `configureHistory` still works, now via `historyRoute`).

- [ ] **Step 5: Build and run the full suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; `ApplicationTest`, `HistoryRoutesTest`, and all others pass. The KSP-generated kotlin-inject component must compile with the new providers.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/di/AppModule.kt src/main/kotlin/di/AppComponent.kt src/main/kotlin/Application.kt src/main/kotlin/history/HistoryRoutes.kt
git commit -m "feat(history): wire HistoryWriter/Repository into DI, startup, and authed /history route"
```

---

## Task 12: ClickHouse schema, Docker Compose, config files

**Files:**
- Create: `deploy/clickhouse-init.sql`
- Modify: `docker-compose.yml`
- Modify: `deploy/config.yaml.template`
- Modify: `src/main/resources/config.yaml`

No unit test (infrastructure); validated by the integration test in Task 13.

- [ ] **Step 1: Write the schema SQL**

Create `deploy/clickhouse-init.sql`:

```sql
-- Applied automatically on first ClickHouse start (mounted into /docker-entrypoint-initdb.d).
CREATE DATABASE IF NOT EXISTS ems;

-- Raw 5-second ticks, retained for 1 year then dropped.
CREATE TABLE IF NOT EXISTS ems.power_raw (
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

-- 1-minute partial aggregates, merged by AggregatingMergeTree, retained indefinitely.
CREATE TABLE IF NOT EXISTS ems.power_1m (
    ts             DateTime,
    grid_power     AggregateFunction(avg, Nullable(Int32)),
    solar_power    AggregateFunction(avg, Nullable(Int32)),
    charger_power  AggregateFunction(avg, Nullable(Int32)),
    heatpump_power AggregateFunction(avg, Nullable(Int32)),
    battery_power  AggregateFunction(avg, Nullable(Int32)),
    battery_charge AggregateFunction(avg, Nullable(Int32))
) ENGINE = AggregatingMergeTree()
ORDER BY ts;

CREATE MATERIALIZED VIEW IF NOT EXISTS ems.power_1m_mv
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

-- One-time backfill (run manually only when migrating an already-populated power_raw; no-op on fresh
-- deploy). Uses -State to match the AggregatingMergeTree format:
-- INSERT INTO ems.power_1m
-- SELECT toStartOfMinute(ts), avgState(grid_power), avgState(solar_power), avgState(charger_power),
--        avgState(heatpump_power), avgState(battery_power), avgState(battery_charge)
-- FROM ems.power_raw GROUP BY toStartOfMinute(ts);
```

- [ ] **Step 2: Add the ClickHouse service to docker-compose.yml**

In `docker-compose.yml`, add the service (after `cloudflared`) and the named volume:

```yaml
  clickhouse:
    image: clickhouse/clickhouse-server:24.8
    container_name: clickhouse
    restart: unless-stopped
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - ./deploy/clickhouse-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
```

Add `clickhouse-data:` under the existing top-level `volumes:` block (which currently has `ems-data:`):
```yaml
volumes:
  ems-data:
  clickhouse-data:
```

- [ ] **Step 3: Add the clickhouse block to the deploy template**

In `deploy/config.yaml.template`, add after the `database:` block:
```yaml
# Power-history store. Reached over the shared Docker bridge as http://clickhouse:8123.
clickhouse:
  enabled: true
  host: clickhouse
  port: 8123
  database: ems
```

- [ ] **Step 4: Document the dev default in the bundled config**

In `src/main/resources/config.yaml`, add a top-level block (disabled, since local dev has no ClickHouse):
```yaml
clickhouse:
  enabled: false
```

- [ ] **Step 5: Verify config still loads**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: PASS (the bundled resource still parses; `clickhouse.enabled` is false by default).

- [ ] **Step 6: Commit**

```bash
git add deploy/clickhouse-init.sql docker-compose.yml deploy/config.yaml.template src/main/resources/config.yaml
git commit -m "feat(history): ClickHouse schema, compose service, and config blocks"
```

---

## Task 13: Testcontainers integration test (docker-gated)

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`
- Create: `src/test/kotlin/history/HistoryIntegrationTest.kt`

This boots a real ClickHouse, applies `deploy/clickhouse-init.sql`, writes via `HistoryWriter`, reads via `HistoryRepository`, and asserts the round-trip. It is skipped (not failed) when no container socket is reachable.

- [ ] **Step 1: Add Testcontainers dependencies**

In `gradle/libs.versions.toml` under `[libraries]`:
```toml
testcontainers-clickhouse = { module = "org.testcontainers:clickhouse", version = "1.20.1" }
clickhouse-jdbc = { module = "com.clickhouse:clickhouse-jdbc", version = "0.6.5" }
```
(Testcontainers' `clickhouse` module needs the JDBC driver on the classpath to perform its readiness wait-strategy query.)

In `build.gradle.kts` test dependencies:
```kotlin
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.clickhouse.jdbc)
```

- [ ] **Step 2: Write the integration test**

Create `src/test/kotlin/history/HistoryIntegrationTest.kt`. The `dockerAvailable()` probe short-circuits via `assumeTrue` so the build stays green without a socket:

```kotlin
package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.konektis.ems.EMSState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.testcontainers.containers.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Instant
import kotlin.test.*

class HistoryIntegrationTest {
    private fun dockerAvailable(): Boolean = runCatching {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)

    private fun state(grid: Int, soc: Int) = EMSState(
        gridPower = grid, gridVoltage = 230, chargerPower = 0, heatpumpPower = 0,
        solarPower = -500, batteryPower = 0, batteryCharge = soc,
    )

    @Test
    fun writeThenQueryRoundTrip() = runTest {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())

        val image = DockerImageName.parse("clickhouse/clickhouse-server:24.8")
            .asCompatibleSubstituteFor("clickhouse/clickhouse-server")
        ClickHouseContainer(image)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("/clickhouse-init.sql"),
                "/docker-entrypoint-initdb.d/init.sql",
            )
            .use { ch ->
            ch.start()

            val cfg = ClickHouseConfig(
                enabled = true, host = ch.host, port = ch.getMappedPort(8123), database = "ems",
            )
            val writer = HistoryWriter(cfg, HttpClient(CIO))
            val repo = HistoryRepository(cfg, HttpClient(CIO))

            val now = Instant.now()
            writer.enqueue(TimestampedEmsState(now, state(-1200, 70)))
            writer.enqueue(TimestampedEmsState(now.plusSeconds(5), state(-1100, 71)))
            writer.flushOnce()

            val result = repo.query(HistoryRange.H1, HistoryResolution.RAW)
            assertEquals(2, result.points.size)
            assertEquals(setOf(-1200, -1100), result.points.map { it.gridPower }.toSet())
        }
    }
}
```

Note on the init SQL: copy `deploy/clickhouse-init.sql` to `src/test/resources/clickhouse-init.sql` so `MountableFile.forClasspathResource("/clickhouse-init.sql")` resolves. Add that copy step:

```bash
cp deploy/clickhouse-init.sql src/test/resources/clickhouse-init.sql
```
(Create `src/test/resources/` if absent.)

- [ ] **Step 3: Run the integration test**

First ensure the podman socket is available (one-time, per the spec):
```bash
systemctl --user enable --now podman.socket
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
```
Run: `./gradlew test --tests "io.konektis.history.HistoryIntegrationTest"`
Expected: PASS when the socket is up; SKIPPED (assumeTrue) when it is not. If the container image pull is slow on first run, allow extra time.

- [ ] **Step 4: Run the full build to confirm green end-to-end**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. On a machine without the socket, the integration test is skipped, not failed.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/test/kotlin/history/HistoryIntegrationTest.kt src/test/resources/clickhouse-init.sql
git commit -m "test(history): docker-gated ClickHouse round-trip integration test"
```

---

## Task 14: Operator docs

**Files:**
- Create: `docs/adding-clickhouse-history.md`
- Modify: `CLAUDE.md` (add history to the project layout + a one-line pointer)

- [ ] **Step 1: Write the operator guide**

Create `docs/adding-clickhouse-history.md` covering: what it stores, enabling it (compose service + `clickhouse.enabled: true`), the `GET /history` API (params + response shape + auth), the podman-socket steps for running the integration test, and the one-time `power_1m` backfill note for migrations. Keep it concise and consistent with the existing `docs/adding-bmw-cardata.md` style.

- [ ] **Step 2: Update CLAUDE.md**

Add a `history/` entry to the Project Layout tree and a row/sentence noting that history is persisted to ClickHouse and served from `GET /history`. Add a pointer line to `docs/adding-clickhouse-history.md`.

- [ ] **Step 3: Commit**

```bash
git add docs/adding-clickhouse-history.md CLAUDE.md
git commit -m "docs(history): operator guide + CLAUDE.md pointers for ClickHouse history"
```

---

## Final Verification

- [ ] **Run the full suite:** `./gradlew build` → BUILD SUCCESSFUL
- [ ] **Confirm the integration test runs** with the podman socket enabled (not just skipped).
- [ ] **Manual smoke (optional, on the NAS):** `docker compose up -d clickhouse`, start the server with `clickhouse.enabled: true`, let it run a few minutes, then `curl -u <wsuser>:<wspass> 'http://localhost:8080/history?range=1h'` and confirm JSON points come back.
