package io.konektis

import kotlinx.serialization.json.Json
import kotlin.test.Test
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
}
