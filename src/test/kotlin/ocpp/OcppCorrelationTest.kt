package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OcppCorrelationTest {

    private fun newService(timeoutSec: Int = 1): OcppService {
        val db = freshTestDb()
        val cfg = OcppConfig(true, 300, 60, callTimeoutSeconds = timeoutSec, acceptUnknownChargePoints = true)
        return OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db), cfg)
            .also { it.initStores() }
    }

    @Test
    fun callResolvesWhenChargerReplies() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        val session = mockk<DefaultWebSocketSession>(relaxed = true)
        coEvery { session.send(any()) } answers { sent.add(firstArg()) }
        svc.registerSession("CP1", session)

        val deferred = async { svc.sendCall("CP1", Action.Reset, buildJsonObject { put("type", "Soft") }) }
        runCurrent() // let the async coroutine run far enough to send the CALL frame

        val sentArray = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        val uniqueId = sentArray[1].jsonPrimitive.content
        svc.completeCall(uniqueId, buildJsonObject { put("status", "Accepted") })

        val result = deferred.await()
        assertEquals("Accepted", result?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun callReturnsNullOnTimeout() = runTest {
        val svc = newService(timeoutSec = 1)
        svc.registerSession("CP2", mockk(relaxed = true))
        val result = svc.sendCall("CP2", Action.Reset, buildJsonObject { put("type", "Soft") })
        assertNull(result)
    }

    @Test
    fun callReturnsNullForUnknownChargePoint() = runTest {
        val svc = newService()
        assertNull(svc.sendCall("NOPE", Action.Reset, buildJsonObject { put("type", "Soft") }))
    }
}
