package io.konektis.ems

import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.ws.WS_JSON
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
    fun `WS_JSON skips server fields this app version does not know`() {
        // A newer server adds a field to DeviceHealth.Online. The strict default Json throws on it,
        // which killed the socket and bricked installed apps; WS_JSON must skip it instead.
        val fromNewerServer = """
            {"type":"online","lastSeenAt":1748000000000,"powerW":1800,"someFutureField":42}
        """.trimIndent()
        val health = WS_JSON.decodeFromString<DeviceHealth>(fromNewerServer)
        assertEquals(DeviceHealth.Online(1748000000000L, 1800), health)
    }

    @Test
    fun `WS_JSON parses the batterySoc field that broke the app`() {
        val withSoc = """
            {"type":"online","lastSeenAt":1748000000000,"powerW":200,"extraInfo":"62% SoC","batterySoc":62}
        """.trimIndent()
        val health = WS_JSON.decodeFromString<DeviceHealth>(withSoc) as DeviceHealth.Online
        assertEquals(62, health.batterySoc)
        assertEquals("62% SoC", health.extraInfo)
    }

    @Test
    fun `StatusState tolerates an unknown top-level field`() {
        val fromNewerServer = """
            {"devices":[],"totalSolarW":3200,"gridW":-800,"batteryW":200,"batteryCharge":62,
             "chargerW":0,"heatpumpW":null,"someFutureTopLevelField":"x"}
        """.trimIndent()
        val state = WS_JSON.decodeFromString<StatusState>(fromNewerServer)
        assertEquals(3200, state.totalSolarW)
        assertEquals(62, state.batteryCharge)
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
