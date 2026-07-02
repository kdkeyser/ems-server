package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.configureSecurity
import io.konektis.ems.EnergyManager
import io.konektis.ems.SurplusPriorityStrategy
import io.konektis.ocpp.db.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class OcppWebUiTest {

    private fun Application.testModule(): Pair<OcppService, EnergyManager> {
        install(WebSockets) { pingPeriod = 30.seconds; timeout = 60.seconds }
        install(ContentNegotiation) { json() }
        configureSecurity(io.konektis.config.WebSocketConfig("admin", "secret"))
        val db = freshTestDb()
        val svc = OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, acceptUnknownChargePoints = true, autoProbeOnBoot = false)).also { it.initStores() }
        val em = EnergyManager(
            io.konektis.devices.World(mockk(relaxed = true), null, emptyMap(), null, null),
            mockk(relaxed = true), SurplusPriorityStrategy(), SqlChargerControlStore(db),
        )
        configureOcppWebUi(svc, em)
        return svc to em
    }

    @Test
    fun rejectsUnauthenticatedRequests() = testApplication {
        application { testModule() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui/api/state").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui/api/idtags").status)
    }

    @Test
    fun servesThePage() = testApplication {
        application { testModule() }
        val resp = client.get("/ocpp-ui") { basicAuth("admin", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("OCPP"))
    }

    @Test
    fun idTagCrud() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule().first }

        val post = client.post("/ocpp-ui/api/idtags") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody("""{"idTag":"TAG1","status":"Accepted"}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)

        val list = client.get("/ocpp-ui/api/idtags") { basicAuth("admin", "secret") }.bodyAsText()
        assertTrue(list.contains("TAG1"))
    }

    @Test
    fun chargerControlRoundTripOverHttp() = testApplication {
        application { testModule() }
        val post = client.post("/ocpp-ui/api/chargepoints/CP1/charger-control") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"FIXED","fixedAmps":20,"charging":false}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)
        val body = client.get("/ocpp-ui/api/chargepoints/CP1/charger-control") { basicAuth("admin", "secret") }.bodyAsText()
        assertTrue(body.contains("FIXED"))
        assertTrue(body.contains("20"))
        assertTrue(body.contains("false"))
    }

    @Test
    fun acceptChargePoint() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule().first }
        startApplication() // testApplication's application{} block is lazy; force it so svc is set
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1")) // creates record (auto-accept on)

        val resp = client.post("/ocpp-ui/api/chargepoints/CP1/accepted") {
            basicAuth("admin", "secret")
            contentType(ContentType.Application.Json)
            setBody("""{"accepted":false}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(svc.listChargePoints().single { it.chargePointId == "CP1" }.accepted)
    }

    @Test
    fun manualStartAndStopSetTheChargingIntent() = testApplication {
        lateinit var em: EnergyManager
        application { em = testModule().second }
        startApplication()

        val stop = client.post("/ocpp-ui/api/chargepoints/CP1/stop") {
            basicAuth("admin", "secret"); contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, stop.status)
        assertFalse(em.chargerControlFlow.value.charging)

        val start = client.post("/ocpp-ui/api/chargepoints/CP1/start") {
            basicAuth("admin", "secret"); contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, start.status)
        assertTrue(em.chargerControlFlow.value.charging)
    }

    @Test
    fun setCurrentSwitchesToFixedMode() = testApplication {
        lateinit var em: EnergyManager
        application { em = testModule().second }
        startApplication()

        val resp = client.post("/ocpp-ui/api/chargepoints/CP1/set-current") {
            basicAuth("admin", "secret"); contentType(ContentType.Application.Json)
            setBody("""{"amps":10.0,"connectorId":1}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(io.konektis.ChargerMode.FIXED, em.chargerControlFlow.value.mode)
        assertEquals(10, em.chargerControlFlow.value.fixedAmps)
        assertTrue(em.chargerControlFlow.value.charging)
    }
}
