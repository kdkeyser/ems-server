package io.konektis

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json

private fun ApplicationTestBuilder.installStatusPage(flow: kotlinx.coroutines.flow.Flow<StatusState?>) {
    application {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 30.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        configureStatusPage(flow)
    }
}

class StatusPageTest {

    @Test
    fun `GET slash status returns 200 with HTML content type`() = testApplication {
        installStatusPage(emptyFlow())
        val response = client.get("/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.contentType()!!.match(ContentType.Text.Html),
            "Expected text/html but got ${response.contentType()}"
        )
    }

    @Test
    fun `GET slash status response body contains EMS`() = testApplication {
        installStatusPage(emptyFlow())
        val body = client.get("/status").bodyAsText()
        assertTrue(body.contains("EMS"), "Expected 'EMS' in response body")
    }

    @Test
    fun `WS slash status-ws sends StatusState JSON when flow emits`() = testApplication {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800), "grid"),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "Connection refused"), "charger")
            ),
            totalSolarW = null,
            gridW = -800,
            batteryW = null,
            batteryCharge = null,
            chargerW = null,
            heatpumpW = null
        )
        installStatusPage(flowOf(state))

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/status-ws") {
            val frame = incoming.receive() as Frame.Text
            val received = Json.decodeFromString<StatusState>(frame.readText())
            assertEquals(state, received)
        }
    }

    @Test
    fun `WS slash status-ws skips null values from flow`() = testApplication {
        installStatusPage(flowOf(null))

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/status-ws") {
            val result = runCatching { incoming.receive() }
            assertTrue(result.isFailure || result.getOrNull() is Frame.Close)
        }
    }
}
