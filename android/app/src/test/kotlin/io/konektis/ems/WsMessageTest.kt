package io.konektis.ems

import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ChargerMode
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WsMessageTest {

    @Test
    fun `SetCharging round-trips`() {
        val msg = ClientMessage.SetCharging(ChargerControl(ChargerMode.FIXED, 16, true))
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `Authenticate round-trips`() {
        val msg = ClientMessage.Authenticate("user", "pass")
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `SetMode round-trips`() {
        val msg = ClientMessage.SetMode(ManagerMode.MANUAL)
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    // The discriminator string is the cross-process contract with the server's Messages.kt —
    // it must be the package-independent @SerialName, not a fully-qualified class name.
    @Test
    fun `SetMode uses the agreed wire discriminator`() {
        val json = Json.encodeToString<ClientMessage>(ClientMessage.SetMode(ManagerMode.AUTO))
        assertTrue(json.contains("\"type\":\"SetMode\""), "unexpected discriminator: $json")
        assertTrue(json.contains("\"mode\":\"AUTO\""), "unexpected mode field: $json")
    }

    @Test
    fun `ModeUpdate round-trips and uses the agreed discriminator`() {
        val msg = Message.ModeUpdate(ManagerMode.MANUAL)
        val json = Json.encodeToString<Message>(msg)
        assertTrue(json.contains("\"type\":\"ModeUpdate\""), "unexpected discriminator: $json")
        assertEquals(msg, Json.decodeFromString<Message>(json))
    }

    @Test
    fun `ChargerControlUpdate round-trips and uses the agreed discriminator`() {
        val msg = Message.ChargerControlUpdate(ChargerControl(ChargerMode.SOLAR, 10, false))
        val json = Json.encodeToString<Message>(msg)
        assertTrue(json.contains("\"type\":\"ChargerControlUpdate\""), "unexpected discriminator: $json")
        assertEquals(msg, Json.decodeFromString<Message>(json))
    }
}
