package io.konektis

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.charger.Charger
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.devices.solar.SolarState
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest

private fun makeWorld(
    solar: Map<String, Solar> = emptyMap(),
    chargers: Map<String, Charger> = emptyMap(),
    batteries: Map<String, Battery> = emptyMap(),
    smartConsumers: Map<String, SmartConsumer> = emptyMap(),
    grid: Grid = mockk<Grid>().also {
        coEvery { it.update() } just runs
        coEvery { it.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            GridState(Watt(0), Volt(230u))
        )
    }
): World = World(grid, chargers, solar, smartConsumers, batteries)

class DataCollectorHealthTest {

    @Test
    fun `refresh records Offline when device update throws`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } throws Exception("Connection refused")
        coEvery { solar.getState() } returns null

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        val status = collector.statusStateFlow.value
        assertNotNull(status)
        val solarStatus = status.devices.first { it.name == "Sunny Boy 4" }
        assertTrue(solarStatus.health is DeviceHealth.Offline)
        assertEquals("Connection refused", (solarStatus.health as DeviceHealth.Offline).lastError)
        assertNull((solarStatus.health as DeviceHealth.Offline).lastSeenAt)
    }

    @Test
    fun `refresh records Online with powerW when device update succeeds`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } just runs
        coEvery { solar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(1800))
        )

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        val status = collector.statusStateFlow.value
        assertNotNull(status)
        val solarStatus = status.devices.first { it.name == "Sunny Boy 4" }
        assertTrue(solarStatus.health is DeviceHealth.Online)
        assertEquals(1800, (solarStatus.health as DeviceHealth.Online).powerW)
    }

    @Test
    fun `refresh preserves lastSeenAt from previous Online when device goes offline`() = runTest {
        val solar = mockk<Solar>()
        var callCount = 0
        coEvery { solar.update() } answers {
            callCount++
            if (callCount >= 2) throw Exception("timeout")
        }
        coEvery { solar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(1800))
        )

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))

        collector.refresh()
        val firstHealth = collector.statusStateFlow.value!!
            .devices.first { it.name == "Sunny Boy 4" }.health as DeviceHealth.Online
        val lastSeen = firstHealth.lastSeenAt

        collector.refresh()
        val offlineHealth = collector.statusStateFlow.value!!
            .devices.first { it.name == "Sunny Boy 4" }.health as DeviceHealth.Offline
        assertEquals(lastSeen, offlineHealth.lastSeenAt)
        assertEquals("timeout", offlineHealth.lastError)
    }

    @Test
    fun `refresh emits null totalSolarW when all solar devices are offline`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } throws Exception("timeout")
        coEvery { solar.getState() } returns null

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        assertNull(collector.statusStateFlow.value!!.totalSolarW)
    }

    @Test
    fun `one failed device does not prevent other devices from being polled`() = runTest {
        val failingSolar = mockk<Solar>()
        coEvery { failingSolar.update() } throws Exception("Connection refused")
        coEvery { failingSolar.getState() } returns null

        val workingSolar = mockk<Solar>()
        coEvery { workingSolar.update() } just runs
        coEvery { workingSolar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(2000))
        )

        val collector = DataCollector(1, makeWorld(
            solar = mapOf("Solar 1" to failingSolar, "Solar 2" to workingSolar)
        ))
        collector.refresh()

        val statuses = collector.statusStateFlow.value!!.devices
        assertTrue(statuses.first { it.name == "Solar 1" }.health is DeviceHealth.Offline)
        assertTrue(statuses.first { it.name == "Solar 2" }.health is DeviceHealth.Online)
    }
}
