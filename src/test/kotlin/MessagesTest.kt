package io.konektis

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun `ChargingStateUpdate uses the agreed discriminator and encodes the state`() {
        val json = Json.encodeToString(
            Message.ChargingStateUpdate(ChargingState.ChargingWithMaxPower(7400u)) as Message
        )
        assertTrue(json.contains("\"type\":\"ChargingStateUpdate\""), "unexpected discriminator: $json")
        assertTrue(json.contains("\"type\":\"ChargingWithMaxPower\""), "missing nested state: $json")
    }
}
