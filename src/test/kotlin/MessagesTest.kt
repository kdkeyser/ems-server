package io.konektis

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun `ChargerControlUpdate uses the agreed discriminator and encodes the control`() {
        val json = Json.encodeToString(
            Message.ChargerControlUpdate(ChargerControl(ChargerMode.FIXED, 16, true)) as Message
        )
        assertTrue(json.contains("\"type\":\"ChargerControlUpdate\""), "unexpected discriminator: $json")
        assertTrue(json.contains("\"mode\":\"FIXED\""), "missing mode: $json")
    }

    @Test
    fun `CarStateUpdate round-trips with the agreed discriminator`() {
        val text = Json.encodeToString(Message.CarStateUpdate(73) as Message)
        assertTrue(text.contains("\"type\":\"CarStateUpdate\""), "unexpected discriminator: $text")
        assertEquals(Message.CarStateUpdate(73), deserializeMessage(text))
    }

    @Test
    fun `CarStateUpdate allows a null soc`() {
        val text = Json.encodeToString(Message.CarStateUpdate(null) as Message)
        assertEquals(Message.CarStateUpdate(null), deserializeMessage(text))
    }
}
