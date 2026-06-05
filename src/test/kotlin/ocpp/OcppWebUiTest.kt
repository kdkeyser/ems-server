package io.konektis.ocpp

import io.konektis.config.OcppConfig
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

    private fun Application.testModule(): OcppService {
        install(WebSockets) { pingPeriod = 30.seconds; timeout = 60.seconds }
        install(ContentNegotiation) { json() }
        val db = freshTestDb()
        val svc = OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, autoProbeOnBoot = false)).also { it.initStores() }
        val em = EnergyManager(
            io.konektis.devices.World(mockk(relaxed = true), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
            mockk(relaxed = true), SurplusPriorityStrategy(), SqlChargerControlStore(db),
        )
        configureOcppWebUi(svc, em)
        return svc
    }

    @Test
    fun servesThePage() = testApplication {
        application { testModule() }
        val resp = client.get("/ocpp-ui")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("OCPP"))
    }

    @Test
    fun idTagCrud() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule() }

        val post = client.post("/ocpp-ui/api/idtags") {
            contentType(ContentType.Application.Json)
            setBody("""{"idTag":"TAG1","status":"Accepted"}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)

        val list = client.get("/ocpp-ui/api/idtags").bodyAsText()
        assertTrue(list.contains("TAG1"))
    }

    @Test
    fun chargerControlRoundTripOverHttp() = testApplication {
        application { testModule() }
        val post = client.post("/ocpp-ui/api/chargepoints/CP1/charger-control") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"FIXED","fixedAmps":20,"charging":false}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)
        val body = client.get("/ocpp-ui/api/chargepoints/CP1/charger-control").bodyAsText()
        assertTrue(body.contains("FIXED"))
        assertTrue(body.contains("20"))
        assertTrue(body.contains("false"))
    }

    @Test
    fun acceptChargePoint() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule() }
        startApplication() // testApplication's application{} block is lazy; force it so svc is set
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1")) // creates record (auto-accept on)

        val resp = client.post("/ocpp-ui/api/chargepoints/CP1/accepted") {
            contentType(ContentType.Application.Json)
            setBody("""{"accepted":false}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(svc.listChargePoints().single { it.chargePointId == "CP1" }.accepted)
    }
}
