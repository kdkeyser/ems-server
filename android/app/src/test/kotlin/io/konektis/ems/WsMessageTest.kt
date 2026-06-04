package io.konektis.ems

import io.konektis.ems.data.model.ChargingState
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
    fun `SetCharging NotCharging round-trips`() {
        val msg = ClientMessage.SetCharging(ChargingState.NotCharging)
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `SetCharging ChargingWithMaxPower round-trips`() {
        val msg = ClientMessage.SetCharging(ChargingState.ChargingWithMaxPower(maxPower = 7400u))
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
    fun `ChargingStateUpdate round-trips and uses the agreed discriminator`() {
        val msg = Message.ChargingStateUpdate(ChargingState.ChargingWithExcessPower)
        val json = Json.encodeToString<Message>(msg)
        assertTrue(json.contains("\"type\":\"ChargingStateUpdate\""), "unexpected discriminator: $json")
        assertEquals(msg, Json.decodeFromString<Message>(json))
    }
}
