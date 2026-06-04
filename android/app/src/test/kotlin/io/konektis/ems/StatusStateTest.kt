package io.konektis.ems

import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusStateTest {

    @Test
    fun `DeviceHealth Online round-trips with type discriminator`() {
        val health = DeviceHealth.Online(lastSeenAt = 1748000000000L, powerW = 1800, extraInfo = "62% SoC")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"online\""))
        assertEquals(health, Json.decodeFromString<DeviceHealth>(json))
    }

    @Test
    fun `DeviceHealth Offline round-trips with type discriminator`() {
        val health = DeviceHealth.Offline(lastSeenAt = null, lastError = "Connection refused")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"offline\""))
        assertEquals(health, Json.decodeFromString<DeviceHealth>(json))
    }

    @Test
    fun `StatusState full round-trip`() {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800), "grid"),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "timeout"), "charger")
            ),
            totalSolarW = 3200, gridW = -800, batteryW = 200,
            batteryCharge = 62, chargerW = 0, heatpumpW = null
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }

    @Test
    fun `StatusState round-trips chargerConnection`() {
        val state = StatusState(
            devices = emptyList(),
            totalSolarW = null, gridW = null, batteryW = null,
            batteryCharge = null, chargerW = 0, heatpumpW = null,
            chargerConnection = "Connected"
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }
}
