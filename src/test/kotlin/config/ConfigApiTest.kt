package io.konektis.config

import io.konektis.configureSecurity
import io.konektis.ocpp.freshTestDb
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigApiTest {
    private val json = ConfigService.DEFAULT_JSON

    private fun baseConfig(source: ConfigSource) = Config(
        grid = Grid(GridMeterType.P1HomeWizard, "192.168.1.1"),
        devices = Devices(),
        ocpp = OcppConfig(true, 300, 60),
        websocket = WebSocketConfig("admin", "secret"),
        configSource = source,
    )

    private fun service(source: ConfigSource): ConfigService =
        ConfigService(baseConfig(source), ConfigStore(freshTestDb()).also { it.init() }).also { it.resolve() }

    private fun ApplicationTestBuilder.installApi(service: ConfigService) {
        application {
            install(ContentNegotiation) { json() }
            configureSecurity(WebSocketConfig("admin", "secret"))
            configureConfigApi(service)
        }
    }

    private suspend fun HttpResponse.config(): ConfigResponse =
        json.decodeFromString<ConfigResponse>(bodyAsText())

    @Test
    fun `GET requires auth`() = testApplication {
        installApi(service(ConfigSource.database))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/config").status)
    }

    @Test
    fun `GET returns source, version and config`() = testApplication {
        installApi(service(ConfigSource.database))
        val resp = client.get("/api/config") { basicAuth("admin", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.config()
        assertEquals(ConfigSource.database, body.source)
        assertEquals("192.168.1.1", body.config.grid.host)
    }

    @Test
    fun `PUT grid in file mode is rejected with 409`() = testApplication {
        installApi(service(ConfigSource.file))
        val resp = client.put("/api/config/grid") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Grid(GridMeterType.P1HomeWizard, "10.0.0.9")))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `PUT grid in database mode updates and is reflected on GET`() = testApplication {
        installApi(service(ConfigSource.database))
        val put = client.put("/api/config/grid") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Grid(GridMeterType.P1HomeWizard, "10.0.0.9")))
        }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals("10.0.0.9", put.config().config.grid.host)

        val get = client.get("/api/config") { basicAuth("admin", "secret") }.config()
        assertEquals("10.0.0.9", get.config.grid.host)
    }

    @Test
    fun `POST adding a solar device succeeds`() = testApplication {
        installApi(service(ConfigSource.database))
        val resp = client.post("/api/config/devices/solar") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Solar(SolarType.SMA_Sunny_Boy, "Roof", "192.168.1.20")))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(1, resp.config().config.devices.solar.size)
    }

    @Test
    fun `POST a second battery fails validation with 422`() = testApplication {
        val svc = service(ConfigSource.database)
        // First battery is fine.
        svc.update(baseConfig(ConfigSource.database).copy(
            devices = Devices(battery = listOf(Battery(BatteryType.SMA_Sunny_Boy_Storage, "A", "1.1.1.1")))
        ))
        installApi(svc)
        val resp = client.post("/api/config/devices/battery") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(Battery(BatteryType.SMA_Sunny_Boy_Storage, "B", "1.1.1.2")))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        val errors = json.decodeFromString<List<ValidationError>>(resp.bodyAsText())
        assertEquals("devices.battery", errors.single().field)
    }
}
