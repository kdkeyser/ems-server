# Grafana Dashboards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a LAN-only Grafana instance to the NAS compose stack, provisioned-as-code, that reads the ClickHouse power history through a dedicated read-only user and ships three standard dashboards plus ad-hoc exploration.

**Architecture:** A `grafana` service joins `docker-compose.yml` on the existing bridge, reaching ClickHouse internally. A read-only ClickHouse user (`grafana`) is defined via a `users.d` XML drop-in. Datasource and dashboards are provisioned from committed files under `deploy/grafana/`. The error-prone part — turning stored Watt samples into kWh — lives in committed `.sql` query files that are tested against a real seeded ClickHouse (Testcontainers, reusing the history feature's harness). Grafana panel rendering is verified by a manual browser smoke test (out of scope to automate).

**Tech Stack:** Grafana OSS 11.4.0 + `grafana-clickhouse-datasource` plugin, ClickHouse 24.8, Docker Compose (run via podman on the NAS), Kotlin/Gradle tests with Testcontainers + Ktor CIO client + kotlinx-serialization + kaml.

**Spec:** `docs/superpowers/specs/2026-06-09-grafana-dashboards-design.md`

---

## File Structure

**New files:**
- `deploy/clickhouse/users.d/grafana.xml` — read-only ClickHouse user (password via `from_env`)
- `deploy/grafana/provisioning/datasources/clickhouse.yaml` — the ClickHouse datasource
- `deploy/grafana/provisioning/dashboards/provider.yaml` — dashboard file provider
- `deploy/grafana/dashboards/power-flow.json` — Power Flow dashboard
- `deploy/grafana/dashboards/energy-balance.json` — Energy Balance dashboard
- `deploy/grafana/dashboards/battery-devices.json` — Battery & Devices dashboard
- `deploy/grafana/queries/daily-grid-energy.sql` — daily grid import/export kWh
- `deploy/grafana/queries/daily-solar-energy.sql` — daily solar produced kWh
- `deploy/grafana/queries/daily-battery-energy.sql` — daily battery charged/discharged kWh
- `deploy/grafana/queries/daily-device-energy.sql` — daily charger/heat-pump kWh
- `docs/adding-grafana-dashboards.md` — operator guide
- Test files under `src/test/kotlin/grafana/`

**Modified files:**
- `docker-compose.yml` — add `grafana` service; add env var + users.d volume to `clickhouse`
- `CLAUDE.md` — pointer to the new doc
- `src/test/resources/` — copies of the user XML for the Testcontainers test

**Why `.sql` files separate from dashboards:** the energy math is the only thing that can silently produce wrong numbers, so it is isolated into files that are unit-tested against real ClickHouse. The dashboards embed the exact same query text (with Grafana's `$__timeFilter(ts)` macro); the tests strip the macro and run the rest against seeded data.

---

## Task 1: Read-only ClickHouse user

**Files:**
- Create: `deploy/clickhouse/users.d/grafana.xml`
- Create: `src/test/resources/grafana-user.xml` (copy of the above, for the test mount)
- Modify: `docker-compose.yml` (clickhouse service: env var + volume)
- Test: `src/test/kotlin/grafana/ClickHouseGrafanaUserTest.kt`

The `grafana` user is read-only (built-in `readonly` profile) and scoped to the `ems` and `system` databases (`system` so the datasource's schema browser works). The password comes from the `GRAFANA_CH_PASSWORD` env var via ClickHouse's `from_env` config attribute — no secret committed.

- [ ] **Step 1: Write the read-only user XML**

Create `deploy/clickhouse/users.d/grafana.xml`:

```xml
<clickhouse>
    <users>
        <grafana>
            <password from_env="GRAFANA_CH_PASSWORD"/>
            <networks><ip>::/0</ip></networks>
            <profile>readonly</profile>
            <quota>default</quota>
            <allow_databases>
                <database>ems</database>
                <database>system</database>
            </allow_databases>
        </grafana>
    </users>
</clickhouse>
```

Copy it to the test resources so the Testcontainers test can mount it from the classpath:
```bash
mkdir -p src/test/resources
cp deploy/clickhouse/users.d/grafana.xml src/test/resources/grafana-user.xml
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/grafana/ClickHouseGrafanaUserTest.kt`. It boots ClickHouse with the schema and the user XML, sets `GRAFANA_CH_PASSWORD`, then asserts the `grafana` user can `SELECT` but is denied `INSERT`. Docker-gated like the history integration test.

```kotlin
package io.konektis.grafana

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import kotlin.test.*

class ClickHouseGrafanaUserTest {
    private fun dockerAvailable(): Boolean = runCatching {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)

    private suspend fun query(client: HttpClient, base: String, sql: String): String =
        client.post(base) {
            parameter("user", "grafana")
            parameter("password", "testpass")
            setBody(sql)
        }.bodyAsText()

    @Test
    fun grafanaUserCanReadButNotWrite() = runBlocking {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())

        val ch = GenericContainer("clickhouse/clickhouse-server:24.8").apply {
            withExposedPorts(8123)
            withEnv("GRAFANA_CH_PASSWORD", "testpass")
            withCopyFileToContainer(
                MountableFile.forClasspathResource("/clickhouse-init.sql"),
                "/docker-entrypoint-initdb.d/init.sql",
            )
            withCopyFileToContainer(
                MountableFile.forClasspathResource("/grafana-user.xml"),
                "/etc/clickhouse-server/users.d/grafana.xml",
            )
            waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200))
        }

        ch.use { c ->
            c.start()
            val client = HttpClient(CIO)
            val base = "http://${c.host}:${c.getMappedPort(8123)}/"

            // SELECT is allowed.
            val read = query(client, base, "SELECT count() FROM ems.power_raw FORMAT TabSeparated")
            assertEquals("0", read.trim(), "grafana user must be able to SELECT")

            // INSERT is denied (readonly profile).
            val write = query(
                client, base,
                "INSERT INTO ems.power_raw (ts, grid_power) VALUES (now(), 1)",
            )
            assertTrue(
                write.contains("readonly", ignoreCase = true) || write.contains("Cannot", ignoreCase = true),
                "INSERT should be rejected for the read-only user, got: $write",
            )
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --tests "io.konektis.grafana.ClickHouseGrafanaUserTest"`
Expected: PASS (the read succeeds returning `0`, the insert is rejected). If the socket is unavailable it SKIPs — that is acceptable but the goal is for it to run.

- [ ] **Step 4: Wire the user into the clickhouse compose service**

In `docker-compose.yml`, modify the `clickhouse` service to pass the env var and mount the user XML (keep the existing `volumes` entries, add the new one):

```yaml
  clickhouse:
    image: clickhouse/clickhouse-server:24.8
    container_name: clickhouse
    restart: unless-stopped
    environment:
      GRAFANA_CH_PASSWORD: ${GRAFANA_CH_PASSWORD:?set in .env}
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - ./deploy/clickhouse-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
      - ./deploy/clickhouse/users.d/grafana.xml:/etc/clickhouse-server/users.d/grafana.xml:ro
    ulimits:
      nofile:
        soft: 262144
        hard: 262144
```

- [ ] **Step 5: Commit**

```bash
git add deploy/clickhouse/users.d/grafana.xml src/test/resources/grafana-user.xml docker-compose.yml src/test/kotlin/grafana/ClickHouseGrafanaUserTest.kt
git commit -m "feat(grafana): read-only ClickHouse user via users.d (from_env password)"
```

---

## Task 2: Tested analytical energy queries

**Files:**
- Create: `deploy/grafana/queries/daily-grid-energy.sql`
- Create: `deploy/grafana/queries/daily-solar-energy.sql`
- Create: `deploy/grafana/queries/daily-battery-energy.sql`
- Create: `deploy/grafana/queries/daily-device-energy.sql`
- Test: `src/test/kotlin/grafana/EnergyQueriesTest.kt`

Each query reads `ems.power_1m` (1-minute averages — an `AggregatingMergeTree`, so it **must** `avgMerge`), sign-splits the channel, and scales each minute's average Watts to kWh with `/60/1000`. Each query contains Grafana's `$__timeFilter(ts)` macro; the test strips it (replaces with `1`) before running against seeded data.

Energy identity used by the fixtures: N minutes at a constant `P` Watts in one channel = `N × P / 60 / 1000` kWh. So 60 minutes at 1000 W = 1.0 kWh.

- [ ] **Step 1: Write the query files**

`deploy/grafana/queries/daily-grid-energy.sql`:
```sql
SELECT
    toDate(ts) AS time,
    sumIf(g, g > 0) / 60.0 / 1000.0 AS imported_kwh,
    sumIf(-g, g < 0) / 60.0 / 1000.0 AS exported_kwh
FROM (
    SELECT ts, avgMerge(grid_power) AS g
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
```

`deploy/grafana/queries/daily-solar-energy.sql`:
```sql
SELECT
    toDate(ts) AS time,
    sumIf(-s, s < 0) / 60.0 / 1000.0 AS solar_produced_kwh
FROM (
    SELECT ts, avgMerge(solar_power) AS s
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
```

`deploy/grafana/queries/daily-battery-energy.sql`:
```sql
SELECT
    toDate(ts) AS time,
    sumIf(b, b > 0) / 60.0 / 1000.0 AS battery_charged_kwh,
    sumIf(-b, b < 0) / 60.0 / 1000.0 AS battery_discharged_kwh
FROM (
    SELECT ts, avgMerge(battery_power) AS b
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
```

`deploy/grafana/queries/daily-device-energy.sql`:
```sql
SELECT
    toDate(ts) AS time,
    sum(c) / 60.0 / 1000.0 AS charger_kwh,
    sum(h) / 60.0 / 1000.0 AS heatpump_kwh
FROM (
    SELECT ts, avgMerge(charger_power) AS c, avgMerge(heatpump_power) AS h
    FROM ems.power_1m
    WHERE $__timeFilter(ts)
    GROUP BY ts
)
GROUP BY time
ORDER BY time
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/grafana/EnergyQueriesTest.kt`. It boots ClickHouse with the schema, seeds `power_1m` (via inserts into `power_raw` so the materialized view populates it), runs each query file with the macro stripped, and asserts the kWh result. Helper `runQueryFile` loads the file from the project tree, replaces `$__timeFilter(ts)` with `1`, and returns the result as TabSeparated text.

```kotlin
package io.konektis.grafana

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.File
import kotlin.test.*

class EnergyQueriesTest {
    private fun dockerAvailable(): Boolean = runCatching {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)

    private suspend fun exec(client: HttpClient, base: String, sql: String): String =
        client.post(base) { setBody(sql) }.bodyAsText()

    private suspend fun runQueryFile(client: HttpClient, base: String, path: String): String {
        val sql = File(path).readText().replace("\$__timeFilter(ts)", "1")
        return exec(client, base, "$sql FORMAT TabSeparated").trim()
    }

    /** Insert [minutes] one-per-minute raw rows on a fixed day with the given channel values.
     *  The materialized view aggregates them into power_1m (one bucket per minute). */
    private suspend fun seed(
        client: HttpClient, base: String, minutes: Int,
        grid: Int? = null, solar: Int? = null, charger: Int? = null,
        heatpump: Int? = null, battery: Int? = null,
    ) {
        fun v(x: Int?) = x?.toString() ?: "NULL"
        val rows = (0 until minutes).joinToString(",") { m ->
            "(toDateTime('2026-01-01 00:00:00') + INTERVAL $m MINUTE," +
                "${v(grid)},${v(solar)},${v(charger)},${v(heatpump)},${v(battery)},NULL)"
        }
        exec(
            client, base,
            "INSERT INTO ems.power_raw " +
                "(ts,grid_power,solar_power,charger_power,heatpump_power,battery_power,battery_charge) " +
                "VALUES $rows",
        )
    }

    /** Parse the named column from a single-data-row TabSeparated result (header order known per query). */
    private fun col(tsv: String, index: Int): Double =
        tsv.lineSequence().first { it.isNotBlank() }.split('\t')[index].toDouble()

    @Test
    fun gridEnergySplitsImportAndExport() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, grid = 2000)   // constant import
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-grid-energy.sql")
            // columns: time, imported_kwh, exported_kwh
            assertEquals(2.0, col(r, 1), 0.001)
            assertEquals(0.0, col(r, 2), 0.001)
        }
    }

    @Test
    fun solarEnergyCountsProduction() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, solar = -1000)  // producing
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-solar-energy.sql")
            assertEquals(1.0, col(r, 1), 0.001) // solar_produced_kwh
        }
    }

    @Test
    fun batteryEnergySplitsChargeDischarge() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, battery = 1500)   // charging (positive)
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-battery-energy.sql")
            // columns: time, charged_kwh, discharged_kwh
            assertEquals(1.5, col(r, 1), 0.001)
            assertEquals(0.0, col(r, 2), 0.001)
        }
    }

    @Test
    fun deviceEnergySumsLoads() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, charger = 3680, heatpump = 800)
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-device-energy.sql")
            // columns: time, charger_kwh, heatpump_kwh
            assertEquals(3.68, col(r, 1), 0.001)
            assertEquals(0.80, col(r, 2), 0.001)
        }
    }

    /** Start ClickHouse with the schema, run [body], tear down. */
    private fun clickhouse(body: suspend (HttpClient, String) -> Unit) = runBlocking {
        val ch = GenericContainer("clickhouse/clickhouse-server:24.8").apply {
            withExposedPorts(8123)
            withCopyFileToContainer(
                MountableFile.forClasspathResource("/clickhouse-init.sql"),
                "/docker-entrypoint-initdb.d/init.sql",
            )
            waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200))
        }
        ch.use { c ->
            c.start()
            body(HttpClient(CIO), "http://${c.host}:${c.getMappedPort(8123)}/")
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew test --tests "io.konektis.grafana.EnergyQueriesTest"`
Expected: PASS — 4 tests, each asserting the exact kWh from a known fixture. (Each test starts its own container; allow time.)

- [ ] **Step 4: Commit**

```bash
git add deploy/grafana/queries src/test/kotlin/grafana/EnergyQueriesTest.kt
git commit -m "feat(grafana): tested daily kWh energy queries (grid/solar/battery/devices)"
```

---

## Task 3: Datasource + dashboard provider provisioning

**Files:**
- Create: `deploy/grafana/provisioning/datasources/clickhouse.yaml`
- Create: `deploy/grafana/provisioning/dashboards/provider.yaml`
- Test: `src/test/kotlin/grafana/ProvisioningFilesTest.kt`

The datasource has a fixed `uid: ems-clickhouse` so the committed dashboards can reference it by a stable id. The provider points Grafana at the committed dashboard JSON directory.

- [ ] **Step 1: Write the datasource provisioning**

Create `deploy/grafana/provisioning/datasources/clickhouse.yaml`:
```yaml
apiVersion: 1
datasources:
  - name: EMS ClickHouse
    uid: ems-clickhouse
    type: grafana-clickhouse-datasource
    access: proxy
    isDefault: true
    jsonData:
      host: clickhouse
      port: 9000
      protocol: native
      defaultDatabase: ems
      username: grafana
    secureJsonData:
      password: $GRAFANA_CH_PASSWORD
    editable: false
```

- [ ] **Step 2: Write the dashboard provider**

Create `deploy/grafana/provisioning/dashboards/provider.yaml`:
```yaml
apiVersion: 1
providers:
  - name: EMS dashboards
    type: file
    allowUiUpdates: true
    disableDeletion: true
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 3: Write the failing test**

Create `src/test/kotlin/grafana/ProvisioningFilesTest.kt`. It parses both YAML files with kaml (throws on malformed YAML — `kaml` is already on the test classpath via `hoplite-yaml`) and asserts the key invariants that, if wrong, would silently break provisioning.

```kotlin
package io.konektis.grafana

import com.charleskorn.kaml.Yaml
import java.io.File
import kotlin.test.*

class ProvisioningFilesTest {
    private fun parse(path: String) = Yaml.default.parseToYamlNode(File(path).readText())

    @Test
    fun datasourceYamlIsValidAndNamesTheClickHouseDatasource() {
        val text = File("deploy/grafana/provisioning/datasources/clickhouse.yaml").readText()
        parse("deploy/grafana/provisioning/datasources/clickhouse.yaml") // throws if malformed
        assertTrue(text.contains("uid: ems-clickhouse"), "datasource must pin uid ems-clickhouse")
        assertTrue(text.contains("type: grafana-clickhouse-datasource"))
        assertTrue(text.contains("username: grafana"), "must connect as the read-only user")
        assertTrue(text.contains("\$GRAFANA_CH_PASSWORD"), "password must come from the env var")
    }

    @Test
    fun providerYamlIsValidAndPointsAtDashboards() {
        val text = File("deploy/grafana/provisioning/dashboards/provider.yaml").readText()
        parse("deploy/grafana/provisioning/dashboards/provider.yaml") // throws if malformed
        assertTrue(text.contains("path: /var/lib/grafana/dashboards"))
        assertTrue(text.contains("disableDeletion: true"))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "io.konektis.grafana.ProvisioningFilesTest"`
Expected: PASS (2 tests). This needs no Docker — it is pure file parsing.

- [ ] **Step 5: Commit**

```bash
git add deploy/grafana/provisioning src/test/kotlin/grafana/ProvisioningFilesTest.kt
git commit -m "feat(grafana): datasource + dashboard provider provisioning"
```

---

## Task 4: Dashboard definitions

**Files:**
- Create: `deploy/grafana/dashboards/power-flow.json`
- Create: `deploy/grafana/dashboards/energy-balance.json`
- Create: `deploy/grafana/dashboards/battery-devices.json`
- Test: `src/test/kotlin/grafana/DashboardsTest.kt`

These are minimal, valid starter dashboards that load and show data; you refine the visuals in the Grafana UI later and re-export (the provider allows UI updates). Each panel references the datasource by uid `ems-clickhouse` and embeds query text (the energy panels embed the exact queries tested in Task 2, macro included).

- [ ] **Step 1: Write power-flow.json**

Create `deploy/grafana/dashboards/power-flow.json`:
```json
{
  "uid": "ems-power-flow",
  "title": "Power Flow",
  "schemaVersion": 39,
  "timezone": "browser",
  "time": { "from": "now-6h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Power (W)",
      "gridPos": { "h": 11, "w": 24, "x": 0, "y": 0 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "watt" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT ts AS time, grid_power, solar_power, charger_power, heatpump_power, battery_power FROM ems.power_raw WHERE $__timeFilter(ts) ORDER BY ts"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "Battery SoC (%)",
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 11 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "percent", "min": 0, "max": 100 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT ts AS time, battery_charge FROM ems.power_raw WHERE $__timeFilter(ts) ORDER BY ts"
        }
      ]
    }
  ]
}
```

- [ ] **Step 2: Write energy-balance.json**

Create `deploy/grafana/dashboards/energy-balance.json` (bar charts; the `rawSql` values are the Task-2 queries inlined as single-line strings):
```json
{
  "uid": "ems-energy-balance",
  "title": "Energy Balance",
  "schemaVersion": 39,
  "timezone": "browser",
  "time": { "from": "now-30d", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "barchart",
      "title": "Grid energy per day (kWh)",
      "gridPos": { "h": 9, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "kwatth" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT toDate(ts) AS time, sumIf(g, g > 0) / 60.0 / 1000.0 AS imported_kwh, sumIf(-g, g < 0) / 60.0 / 1000.0 AS exported_kwh FROM (SELECT ts, avgMerge(grid_power) AS g FROM ems.power_1m WHERE $__timeFilter(ts) GROUP BY ts) GROUP BY time ORDER BY time"
        }
      ]
    },
    {
      "id": 2,
      "type": "barchart",
      "title": "Solar produced per day (kWh)",
      "gridPos": { "h": 9, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "kwatth" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT toDate(ts) AS time, sumIf(-s, s < 0) / 60.0 / 1000.0 AS solar_produced_kwh FROM (SELECT ts, avgMerge(solar_power) AS s FROM ems.power_1m WHERE $__timeFilter(ts) GROUP BY ts) GROUP BY time ORDER BY time"
        }
      ]
    },
    {
      "id": 3,
      "type": "barchart",
      "title": "Device energy per day (kWh)",
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 9 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "kwatth" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT toDate(ts) AS time, sum(c) / 60.0 / 1000.0 AS charger_kwh, sum(h) / 60.0 / 1000.0 AS heatpump_kwh FROM (SELECT ts, avgMerge(charger_power) AS c, avgMerge(heatpump_power) AS h FROM ems.power_1m WHERE $__timeFilter(ts) GROUP BY ts) GROUP BY time ORDER BY time"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: Write battery-devices.json**

Create `deploy/grafana/dashboards/battery-devices.json`:
```json
{
  "uid": "ems-battery-devices",
  "title": "Battery & Devices",
  "schemaVersion": 39,
  "timezone": "browser",
  "time": { "from": "now-24h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Battery power (W) and SoC (%)",
      "gridPos": { "h": 10, "w": 24, "x": 0, "y": 0 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": {}, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT ts AS time, battery_power, battery_charge FROM ems.power_raw WHERE $__timeFilter(ts) ORDER BY ts"
        }
      ]
    },
    {
      "id": 2,
      "type": "barchart",
      "title": "Battery throughput per day (kWh)",
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 10 },
      "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
      "fieldConfig": { "defaults": { "unit": "kwatth" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "grafana-clickhouse-datasource", "uid": "ems-clickhouse" },
          "queryType": "sql",
          "rawSql": "SELECT toDate(ts) AS time, sumIf(b, b > 0) / 60.0 / 1000.0 AS battery_charged_kwh, sumIf(-b, b < 0) / 60.0 / 1000.0 AS battery_discharged_kwh FROM (SELECT ts, avgMerge(battery_power) AS b FROM ems.power_1m WHERE $__timeFilter(ts) GROUP BY ts) GROUP BY time ORDER BY time"
        }
      ]
    }
  ]
}
```

- [ ] **Step 4: Write the failing test**

Create `src/test/kotlin/grafana/DashboardsTest.kt`. For every dashboard JSON it asserts: parses as JSON (throws if malformed), has a non-empty `panels` array, every panel/target references the `ems-clickhouse` datasource uid, and every `rawSql` reads an `ems.` table.

```kotlin
package io.konektis.grafana

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class DashboardsTest {
    private val dir = File("deploy/grafana/dashboards")

    private fun dashboards(): List<File> =
        dir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()

    @Test
    fun thereAreThreeDashboards() {
        assertEquals(
            setOf("power-flow.json", "energy-balance.json", "battery-devices.json"),
            dashboards().map { it.name }.toSet(),
        )
    }

    @Test
    fun everyDashboardIsValidAndWiredToTheDatasource() {
        assertTrue(dashboards().isNotEmpty())
        for (f in dashboards()) {
            val root = Json.parseToJsonElement(f.readText()).jsonObject // throws if malformed
            assertNotNull(root["uid"], "${f.name} needs a uid")
            val panels = root["panels"]!!.jsonArray
            assertTrue(panels.isNotEmpty(), "${f.name} has no panels")
            for (panel in panels) {
                val targets = panel.jsonObject["targets"]?.jsonArray ?: continue
                for (t in targets) {
                    val ds = t.jsonObject["datasource"]!!.jsonObject["uid"]!!.jsonPrimitive.content
                    assertEquals("ems-clickhouse", ds, "${f.name}: target must use the EMS datasource")
                    val sql = t.jsonObject["rawSql"]!!.jsonPrimitive.content
                    assertTrue(sql.contains("ems."), "${f.name}: rawSql must read an ems.* table")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "io.konektis.grafana.DashboardsTest"`
Expected: PASS (2 tests). No Docker needed.

- [ ] **Step 6: Commit**

```bash
git add deploy/grafana/dashboards src/test/kotlin/grafana/DashboardsTest.kt
git commit -m "feat(grafana): three starter dashboards (power flow, energy balance, battery/devices)"
```

---

## Task 5: Grafana compose service

**Files:**
- Modify: `docker-compose.yml`

No automated test (declarative infra; verified by the manual smoke test in Task 6 and the file-validity tests already written). The compose file's interpolation is checked with `docker compose config` if available.

- [ ] **Step 1: Add the grafana service**

In `docker-compose.yml`, add the `grafana` service after `clickhouse`, and (the `grafana-data` volume) to the top-level `volumes:` block:

```yaml
  grafana:
    image: grafana/grafana-oss:11.4.0
    container_name: grafana
    restart: unless-stopped
    ports:
      - "3000:3000"
    environment:
      GF_INSTALL_PLUGINS: grafana-clickhouse-datasource 4.5.1
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?set in .env}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
      GRAFANA_CH_PASSWORD: ${GRAFANA_CH_PASSWORD:?set in .env}
    volumes:
      - ./deploy/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./deploy/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - clickhouse
```

The top-level `volumes:` block becomes:
```yaml
volumes:
  ems-data:
  clickhouse-data:
  grafana-data:
```

Note: `GRAFANA_CH_PASSWORD` is passed to the grafana container too, because the datasource provisioning interpolates `$GRAFANA_CH_PASSWORD` at Grafana start.

- [ ] **Step 2: Validate the compose file parses with interpolation**

Run (only if `docker compose` is available in the dev env; otherwise skip — the file-validity is also covered by the YAML being well-formed):
```bash
GRAFANA_ADMIN_PASSWORD=x GRAFANA_CH_PASSWORD=y TUNNEL_TOKEN=z docker compose -f docker-compose.yml config -q && echo "compose OK"
```
Expected: `compose OK` with no errors. If `docker compose` is not installed, confirm the YAML is well-formed instead:
```bash
python3 -c "import yaml,sys; yaml.safe_load(open('docker-compose.yml')); print('yaml OK')"
```
Expected: `yaml OK`.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(grafana): add LAN-only Grafana service to the compose stack"
```

---

## Task 6: Operator docs + final verification

**Files:**
- Create: `docs/adding-grafana-dashboards.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Write the operator guide**

Create `docs/adding-grafana-dashboards.md` covering: what it is (LAN-only Grafana over the ClickHouse history, read-only); the two `.env` vars to set (`GRAFANA_ADMIN_PASSWORD`, `GRAFANA_CH_PASSWORD`); how to reach it (`http://<nas-ip>:3000`, log in as `admin`); the three dashboards; how exploration works (Explore mode + the query builder/raw SQL, read-only so it's safe); and the dashboards-as-code workflow (edit in UI → export JSON → commit to `deploy/grafana/dashboards/`). Keep it concise and match the style of `docs/adding-clickhouse-history.md`. Include this manual smoke test:

```
1. On the NAS: set GRAFANA_ADMIN_PASSWORD and GRAFANA_CH_PASSWORD in .env, then
   `docker compose up -d clickhouse grafana`.
2. Open http://<nas-ip>:3000, log in as admin.
3. Configuration → Data sources → "EMS ClickHouse" → Save & test → expect "Data source is working".
4. Dashboards → open "Power Flow" → expect live power lines once the EMS has written data.
5. Explore → pick "EMS ClickHouse" → run `SELECT count() FROM ems.power_raw` → expect a number.
```

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, add a `See` pointer line after the ClickHouse history pointer:
```
See [docs/adding-grafana-dashboards.md](docs/adding-grafana-dashboards.md) for the LAN-only Grafana dashboards over the ClickHouse history.
```

- [ ] **Step 3: Run the full suite to confirm green**

Run: `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build`
Expected: `BUILD SUCCESSFUL` — the new `io.konektis.grafana.*` tests pass alongside everything else. (Without the podman socket, the container-backed grafana tests self-skip; the file-validity tests still run.)

- [ ] **Step 4: Commit**

```bash
git add docs/adding-grafana-dashboards.md CLAUDE.md
git commit -m "docs(grafana): operator guide + CLAUDE.md pointer"
```

---

## Final Verification

- [ ] **All tests green:** `./gradlew build` (with the podman socket for the container-backed tests).
- [ ] **Manual smoke** (on the NAS or locally with `docker compose up -d clickhouse grafana`): datasource tests OK, dashboards render, Explore runs ad-hoc SQL.
- [ ] **No secrets committed:** `grep -rn "GRAFANA_CH_PASSWORD\|GRAFANA_ADMIN_PASSWORD" deploy/ docker-compose.yml` shows only env-var references, never a literal password.
