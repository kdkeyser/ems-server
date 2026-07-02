package io.konektis

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataCollectorSocTest {

    @Test
    fun `battery SoC travels as a typed field, not a display string`() = runTest {
        val grid = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returns
                DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(0), Volt(230u)))
        }
        val battery = mockk<Battery>(relaxed = true).also {
            coEvery { it.getState() } returns
                DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(73u, Watt(500)))
        }
        val collector = DataCollector(1, World(grid, null, emptyMap(), null, battery))
        collector.refresh()
        val status = collector.statusStateFlow.value!!
        assertEquals(73, status.batteryCharge)
        val health = status.devices.single { it.name == "battery" }.health as DeviceHealth.Online
        assertEquals(73, health.batterySoc)
    }
}
