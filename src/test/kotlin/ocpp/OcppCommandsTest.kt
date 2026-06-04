package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OcppCommandsTest {

    private fun newService(): OcppService {
        val db = freshTestDb()
        return OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, callTimeoutSeconds = 1)).also { it.initStores() }
    }

    private fun capturingSession(into: MutableList<Frame>): DefaultWebSocketSession =
        mockk<DefaultWebSocketSession>(relaxed = true).also { coEvery { it.send(any()) } answers { into.add(firstArg()) } }

    @Test
    fun setChargingProfileSendsProfileAndReturnsTrueOnAccepted() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.setChargingProfile("CP1", connectorId = 1, limit = 16.0, unit = ChargingRateUnitType.A) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("SetChargingProfile", arr[2].jsonPrimitive.content)
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }

    @Test
    fun resetSendsResetCall() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.reset("CP1", ResetType.Soft) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("Reset", arr[2].jsonPrimitive.content)
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }

    @Test
    fun clearChargingProfileSendsCallWithConnector() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.clearChargingProfile("CP1", connectorId = 1) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("ClearChargingProfile", arr[2].jsonPrimitive.content)
        assertEquals(1, arr[3].jsonObject["connectorId"]?.jsonPrimitive?.int)
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }

    @Test
    fun clearChargingProfileOmitsConnectorWhenNull() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.clearChargingProfile("CP1", connectorId = null) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("ClearChargingProfile", arr[2].jsonPrimitive.content)
        assertFalse(arr[3].jsonObject.containsKey("connectorId"))
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }

    @Test
    fun commandReturnsFalseWhenChargerRepliesError() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.reset("CP1", ResetType.Soft) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        // Charger replies with a CALL_ERROR -> failCall -> sendCall returns null -> reset() returns false
        svc.failCall(arr[1].jsonPrimitive.content, "InternalError: boom")
        assertFalse(job.await())
    }
}
