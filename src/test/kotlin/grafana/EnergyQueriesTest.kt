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

    private fun col(tsv: String, index: Int): Double =
        tsv.lineSequence().first { it.isNotBlank() }.split('\t')[index].toDouble()

    @Test
    fun gridEnergySplitsImportAndExport() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, grid = 2000)
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-grid-energy.sql")
            assertEquals(2.0, col(r, 1), 0.001)
            assertEquals(0.0, col(r, 2), 0.001)
        }
    }

    @Test
    fun solarEnergyCountsProduction() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, solar = -1000)
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-solar-energy.sql")
            assertEquals(1.0, col(r, 1), 0.001)
        }
    }

    @Test
    fun batteryEnergySplitsChargeDischarge() {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())
        clickhouse { client, base ->
            seed(client, base, minutes = 60, battery = 1500)
            val r = runQueryFile(client, base, "deploy/grafana/queries/daily-battery-energy.sql")
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
            assertEquals(3.68, col(r, 1), 0.001)
            assertEquals(0.80, col(r, 2), 0.001)
        }
    }

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
