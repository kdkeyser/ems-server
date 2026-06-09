package io.konektis.history

import io.konektis.config.ClickHouseConfig
import io.konektis.ems.EMSState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/**
 * Boots a real ClickHouse, applies deploy/clickhouse-init.sql, writes via HistoryWriter, and reads
 * back via HistoryRepository. Docker-gated: skips (not fails) when no container socket is reachable.
 *
 * Uses a GenericContainer with an HTTP /ping wait rather than the JDBC-based ClickHouseContainer —
 * our code only ever talks to ClickHouse over HTTP, and the JDBC readiness probe is incompatible with
 * recent clickhouse-jdbc. The image entrypoint applies the init scripts before the foreground server
 * starts accepting connections, so once /ping returns 200 the schema already exists.
 *
 * Run with the podman socket exposed:
 *   DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true \
 *     ./gradlew test --tests "io.konektis.history.HistoryIntegrationTest"
 */
class HistoryIntegrationTest {
    private fun dockerAvailable(): Boolean = runCatching {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    }.getOrDefault(false)

    private fun state(grid: Int, soc: Int) = EMSState(
        gridPower = grid, gridVoltage = 230, chargerPower = 0, heatpumpPower = 0,
        solarPower = -500, batteryPower = 0, batteryCharge = soc,
    )

    @Test
    fun writeThenQueryRoundTrip() = runBlocking {
        assumeTrue("Docker/podman socket not available; skipping", dockerAvailable())

        val ch = GenericContainer("clickhouse/clickhouse-server:24.8").apply {
            withExposedPorts(8123)
            withCopyFileToContainer(
                MountableFile.forClasspathResource("/clickhouse-init.sql"),
                "/docker-entrypoint-initdb.d/init.sql",
            )
            waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200))
        }

        ch.use { container ->
            container.start()

            val cfg = ClickHouseConfig(
                enabled = true, host = container.host, port = container.getMappedPort(8123), database = "ems",
            )
            val writer = HistoryWriter(cfg, HttpClient(CIO))
            val repo = HistoryRepository(cfg, HttpClient(CIO))

            // Pin both ticks to the same wall-clock minute so the 1-minute aggregate is deterministic
            // (one bucket) and both rows fall inside the H1 window.
            val minute = Instant.now().truncatedTo(ChronoUnit.MINUTES)
            writer.enqueue(TimestampedEmsState(minute, state(-1200, 70)))
            writer.enqueue(TimestampedEmsState(minute.plusSeconds(5), state(-1100, 71)))
            writer.flushOnce()

            // Raw path: both ticks come back unaggregated from power_raw.
            val raw = repo.query(HistoryRange.H1, HistoryResolution.RAW)
            assertEquals(2, raw.points.size)
            assertEquals(setOf(-1200, -1100), raw.points.map { it.gridPower }.toSet())

            // 1-minute path: the materialized view + avgMerge collapse both ticks into one bucket
            // averaging the two values (grid avg(-1200,-1100) = -1150, soc avg(70,71) rounds to 71).
            val minuteAgg = repo.query(HistoryRange.D365, HistoryResolution.MINUTE)
            assertEquals(1, minuteAgg.points.size, "both ticks share one minute bucket")
            assertEquals(-1150, minuteAgg.points[0].gridPower)
            assertEquals(71, minuteAgg.points[0].batteryCharge)
        }
    }
}
