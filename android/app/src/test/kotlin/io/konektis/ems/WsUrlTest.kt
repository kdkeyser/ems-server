package io.konektis.ems

import io.konektis.ems.data.ws.wsUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class WsUrlTest {
    @Test
    fun `plain ws for LAN host with port`() {
        assertEquals("ws://10.0.2.2:8080/ws", wsUrl("10.0.2.2:8080", useTls = false, path = "/ws"))
    }

    @Test
    fun `wss for remote host without port`() {
        assertEquals(
            "wss://ems.kenas.be/status-ws",
            wsUrl("ems.kenas.be", useTls = true, path = "/status-ws")
        )
    }

    @Test
    fun `strips any scheme already present in serverUrl`() {
        assertEquals("wss://host/ws", wsUrl("https://host", useTls = true, path = "/ws"))
        assertEquals("ws://host:8080/ws", wsUrl("ws://host:8080", useTls = false, path = "/ws"))
    }

    @Test
    fun `trims trailing slash on host`() {
        assertEquals("wss://host/ws", wsUrl("host/", useTls = true, path = "/ws"))
    }
}
