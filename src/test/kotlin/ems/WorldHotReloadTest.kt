package io.konektis.ems

import io.konektis.GlobalTimeSource
import io.konektis.config.Config
import io.konektis.config.Devices as DevicesConfig
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.WorldHolder
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

private fun grid(power: Int): Grid = mockk<Grid>(relaxed = true).also {
    coEvery { it.update() } just runs
    coEvery { it.getState() } returns
        DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(power), Volt(230u)))
}

private fun battery(power: Int): Battery = mockk<Battery>(relaxed = true).also {
    coEvery { it.getState() } returns
        DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(50u, Watt(power)))
}

private fun cfg() = Config(
    grid = mockk(relaxed = true), devices = DevicesConfig(),
    ocpp = mockk(relaxed = true), websocket = mockk(relaxed = true), refreshThreads = 1,
)

class WorldHotReloadTest {

    @Test fun `WorldHolder swap returns the old world and installs the new one`() {
        val w1 = World(grid(0), null, emptyMap(), null, null)
        val w2 = World(grid(0), null, emptyMap(), null, null)
        val holder = WorldHolder(w1)
        assertSame(w1, holder.current)
        assertSame(w1, holder.swap(w2))
        assertSame(w2, holder.current)
    }

    @Test fun `World shutdown hands the battery back to the inverter`() = runTest {
        val bat = battery(0)
        World(grid(0), null, emptyMap(), null, bat).shutdown()
        coVerify { bat.releaseToInverter() }
    }

    @Test fun `EnergyManager steers the battery of the swapped-in world after a reload`() = runTest {
        val batA = battery(0)
        val holder = WorldHolder(World(grid(-1000), null, emptyMap(), null, batA))
        val em = EnergyManager(holder, { cfg() }, SurplusPriorityStrategy(), FakeChargerControlStore())

        // Degraded path (no charger/heat pump): steer the battery to cancel the grid. target = bat - grid.
        em.tick()
        coVerify { batA.setChargingPower(Watt(1000)) }

        // Hot-reload to a new graph with a different battery and grid reading.
        val batB = battery(0)
        holder.swap(World(grid(-2000), null, emptyMap(), null, batB))
        em.tick()
        coVerify { batB.setChargingPower(Watt(2000)) }
        // The new battery is only ever commanded with the post-swap value.
        coVerify(exactly = 0) { batB.setChargingPower(Watt(1000)) }
    }
}
