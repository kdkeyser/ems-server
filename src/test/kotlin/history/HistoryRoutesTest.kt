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
