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
