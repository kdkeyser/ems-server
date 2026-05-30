package io.konektis.ems

import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
