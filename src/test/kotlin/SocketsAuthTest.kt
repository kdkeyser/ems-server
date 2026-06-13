package io.konektis

import io.konektis.config.WebSocketConfig
import io.konektis.devices.World
import io.konektis.ems.EnergyManager
import io.konektis.ems.FakeChargerControlStore
import io.konektis.ems.SurplusPriorityStrategy
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.*
import io.ktor.websocket.*
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue

class SocketsAuthTest {

    private fun ApplicationTestBuilder.installSockets() {
        application {
            val em = EnergyManager(
                World(mockk(relaxed = true), null, emptyMap(), null, null),
                mockk(relaxed = true), SurplusPriorityStrategy(), FakeChargerControlStore(),
            )
            configureSockets(em, WebSocketConfig("user", "pw"))
        }
    }

    @Test fun `wrong password gets Unauthorized`() = testApplication {
        installSockets()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/ws") {
            send(Frame.Text("""{"type":"Authenticate","username":"user","password":"wrong"}"""))
            val reply = (incoming.receive() as Frame.Text).readText()
            assertTrue(reply.contains("Unauthorized"), "expected Unauthorized, got: $reply")
        }
    }

    @Test fun `correct password gets Authenticated`() = testApplication {
        installSockets()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/ws") {
            send(Frame.Text("""{"type":"Authenticate","username":"user","password":"pw"}"""))
            val reply = (incoming.receive() as Frame.Text).readText()
            assertTrue(reply.contains("Authenticated"), "expected Authenticated, got: $reply")
        }
    }
}
