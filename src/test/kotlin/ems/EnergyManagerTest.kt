package io.konektis.ems

import io.konektis.GlobalTimeSource
import io.konektis.config.ChargingCurrent
import io.konektis.config.Config
import io.konektis.config.Charger as ChargerConfig
import io.konektis.config.ChargerType
import io.konektis.config.Devices as DevicesConfig
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.charger.Charger
import io.konektis.devices.charger.ChargerState
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private fun grid(power: Int?): Grid = mockk<Grid>().also {
    coEvery { it.update() } just runs
    coEvery { it.getState() } returns power?.let { p ->
        DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(p), Volt(230u)))
    }
}

private fun battery(power: Int?): Battery = mockk<Battery>(relaxed = true).also {
    coEvery { it.getState() } returns power?.let { p ->
        DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(50u, Watt(p)))
    }
}

private fun charger(power: Int?): Charger = mockk<Charger>(relaxed = true).also {
    coEvery { it.getState() } returns power?.let { p ->
        DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(p)))
    }
}

private fun heatpump(power: Int?): SmartConsumer = mockk<SmartConsumer>(relaxed = true).also {
    coEvery { it.getState() } returns power?.let { p ->
        DeviceUpdate(GlobalTimeSource.source.markNow(), SmartConsumerState(Watt(p), ConsumeMode.Unrestricted))
    }
}

private fun config() = Config(
    grid = mockk(relaxed = true),
    devices = DevicesConfig(
        charger = listOf(ChargerConfig(ChargerType.WebastoUnite, "c", "h", ChargingCurrent(6.0, 32.0)))
    ),
    ocpp = mockk(relaxed = true),
    websocket = mockk(relaxed = true),
    refreshThreads = 1
)

class EnergyManagerTest {

    private fun manager(world: World) = EnergyManager(world, config(), SurplusPriorityStrategy())

    @Test fun `tier1 full data runs cascade and sets battery remainder`() = runTest {
        val bat = battery(0)
        // grid -3000 exporting, charger 0, heat pump 0 → available 3000 → charger 13A (2990W) → battery 10W
        val world = World(grid(-3000), mapOf("c" to charger(0)), emptyMap(),
            mapOf("h" to heatpump(0)), mapOf("b" to bat))
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(10)) }
    }

    @Test fun `tier2 missing heatpump still balances battery on grid`() = runTest {
        val bat = battery(200)
        // charger present, heat pump missing → degraded; decideDegraded(600,200)=200-600=-400
        val world = World(grid(600), mapOf("c" to charger(0)), emptyMap(),
            emptyMap(), mapOf("b" to bat))
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(-400)) }
    }

    @Test fun `tier2 does not command charger`() = runTest {
        val ch = charger(0)
        val bat = battery(0)
        // heat pump missing → tier2; charger must not be commanded
        val world = World(grid(0), mapOf("c" to ch), emptyMap(), emptyMap(), mapOf("b" to bat))
        manager(world).tick()
        coVerify(exactly = 0) { ch.setMaxChargerPower(any()) }
    }

    @Test fun `blind releases after 6 ticks then once`() = runTest {
        val bat = battery(0)
        val world = World(grid(null), emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        repeat(5) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() }
        m.tick() // 6th
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick() // 7th — not re-fired
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }

    @Test fun `good tick resets blind counter`() = runTest {
        val bat = battery(0)
        val g = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returnsMany listOf(
                null, null, null,
                DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(0), Volt(230u))),
                null, null, null
            )
        }
        val world = World(g, emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        repeat(7) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() } // never 6 consecutive blind
    }

    @Test fun `switching to MANUAL releases battery once`() = runTest {
        val bat = battery(0)
        val world = World(grid(-1000), mapOf("c" to charger(0)), emptyMap(), emptyMap(), mapOf("b" to bat))
        val m = manager(world)
        m.tick()                       // AUTO
        clearMocks(bat, answers = false, recordedCalls = true)
        m.setMode(Mode.MANUAL)
        m.tick()                       // transition → release
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick()                       // stays manual, no further release
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }
}
