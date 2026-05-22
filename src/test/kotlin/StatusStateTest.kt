// src/test/kotlin/StatusStateTest.kt
package io.konektis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusStateTest {

    @Test
    fun `DeviceHealth Online round-trips through JSON with type discriminator`() {
        val health = DeviceHealth.Online(lastSeenAt = 1748000000000L, powerW = 1800, extraInfo = "62% SoC")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"online\""), "Expected type discriminator in: $json")
        val decoded = Json.decodeFromString<DeviceHealth>(json)
        assertEquals(health, decoded)
    }

    @Test
    fun `DeviceHealth Offline with null lastSeenAt round-trips through JSON`() {
        val health = DeviceHealth.Offline(lastSeenAt = null, lastError = "Connection refused")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"offline\""), "Expected type discriminator in: $json")
        val decoded = Json.decodeFromString<DeviceHealth>(json)
        assertEquals(health, decoded)
    }

    @Test
    fun `DeviceHealth Offline with lastSeenAt round-trips through JSON`() {
        val health = DeviceHealth.Offline(lastSeenAt = 1748000000000L, lastError = "Timeout")
        assertEquals(health, Json.decodeFromString<DeviceHealth>(Json.encodeToString<DeviceHealth>(health)))
    }

    @Test
    fun `StatusState with mixed online and offline devices round-trips through JSON`() {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800), "grid"),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "Connection refused"), "charger")
            ),
            totalSolarW = 3400,
            gridW = -800,
            batteryW = 300,
            batteryCharge = 62,
            chargerW = 0,
            heatpumpW = 1200
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }
}
