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

            val read = query(client, base, "SELECT count() FROM ems.power_raw FORMAT TabSeparated")
            assertEquals("0", read.trim(), "grafana user must be able to SELECT")

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
