package io.konektis.ems

import io.konektis.ChargerControl
import io.konektis.ChargerMode
import io.konektis.GlobalTimeSource
import io.konektis.ManagerMode
import io.konektis.config.ChargingCurrent
import io.konektis.config.Config
import io.konektis.config.Charger as ChargerConfig
import io.konektis.config.ChargerType
import io.konektis.config.Devices as DevicesConfig
import io.konektis.devices.Ampere
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.charger.Charger
import io.konektis.devices.charger.ChargerCommand
import io.konektis.devices.charger.ChargerConnection
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
import kotlin.test.assertEquals
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

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

    private fun manager(world: World) = EnergyManager(world, config(), SurplusPriorityStrategy(), FakeChargerControlStore())

    @Test fun `tier1 full data runs cascade and balances the measured grid`() = runTest {
        val bat = battery(0)
        // grid -3000 exporting, charger 0, heat pump 0 -> charger gets 13A (2990W).
        // Battery balances the MEASURED grid: target = 0 - (-3000) = +3000W.
        val world = World(grid(-3000), charger(0), emptyMap(), heatpump(0), bat)
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(3000)) }
    }

    @Test fun `tier2 missing heatpump still balances battery on grid`() = runTest {
        val bat = battery(200)
        // charger present, heat pump missing → degraded; decideDegraded(600,200)=200-600=-400
        val world = World(grid(600), charger(0), emptyMap(), null, bat)
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(-400)) }
    }

    @Test fun `tier2 does not command charger`() = runTest {
        val ch = charger(0)
        val bat = battery(0)
        // heat pump missing → tier2; charger must not be commanded
        val world = World(grid(0), ch, emptyMap(), null, bat)
        manager(world).tick()
        coVerify(exactly = 0) { ch.apply(any()) }
    }

    @Test fun `blind releases from the 6th tick onward`() = runTest {
        val bat = battery(0)
        val world = World(grid(null), null, emptyMap(), null, bat)
        val m = manager(world)
        repeat(5) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() }
        m.tick() // 6th — first release
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick() // 7th — retried each blind tick (SMABattery makes repeat releases a no-op)
        coVerify(exactly = 2) { bat.releaseToInverter() }
    }

    @Test fun `blind stops a solar-mode charger from the release tick onward`() = runTest {
        val ch = charger(0)
        val world = World(grid(null), ch, emptyMap(), null, battery(0))
        val m = manager(world)
        repeat(5) { m.tick() }
        coVerify(exactly = 0) { ch.apply(any()) } // solar mode: uncommanded while briefly blind
        m.tick() // 6th blind tick — battery released AND charger stopped
        coVerify(exactly = 1) { ch.apply(ChargerCommand.Stop) }
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
        val world = World(g, null, emptyMap(), null, bat)
        val m = manager(world)
        repeat(7) { m.tick() }
        coVerify(exactly = 0) { bat.releaseToInverter() } // never 6 consecutive blind
    }

    @Test fun `switching to MANUAL releases battery once`() = runTest {
        val bat = battery(0)
        val world = World(grid(-1000), charger(0), emptyMap(), null, bat)
        val m = manager(world)
        m.tick()                       // AUTO
        clearMocks(bat, answers = false, recordedCalls = true)
        m.setMode(ManagerMode.MANUAL)
        m.tick()                       // transition → release
        coVerify(exactly = 1) { bat.releaseToInverter() }
        m.tick()                       // stays manual, no further release
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }

    @Test fun `setCharging updates chargerControlFlow`() = runTest {
        val world = World(grid(0), charger(0), emptyMap(), null, battery(0))
        val m = manager(world)
        m.setCharging(ChargerControl(charging = false))
        assertEquals(false, m.chargerControlFlow.value.charging)
    }

    @Test fun `stop forces charger to zero amps in AUTO`() = runTest {
        val ch = charger(0)
        // Strong export would normally give the charger surplus amps; Stop overrides to 0.
        val world = World(grid(-5000), ch, emptyMap(), heatpump(0), battery(0))
        val m = manager(world)
        m.setCharging(ChargerControl(ChargerMode.SOLAR, 16, charging = false))
        m.tick()
        coVerify { ch.apply(ChargerCommand.Stop) }
    }

    @Test fun `fixed mode sets clamped amps in AUTO`() = runTest {
        val ch = charger(0)
        val world = World(grid(0), ch, emptyMap(), heatpump(0), battery(0))
        val m = manager(world)
        m.setCharging(ChargerControl(ChargerMode.FIXED, 16, true))
        m.tick()
        coVerify { ch.apply(ChargerCommand.Charge(Ampere(16))) }
    }

    @Test fun `MANUAL auto-reverts solar to fixed, battery untouched`() = runTest {
        val ch = charger(0)
        val bat = battery(0)
        val world = World(grid(-3000), ch, emptyMap(), heatpump(0), bat)
        val m = manager(world)
        m.setMode(ManagerMode.MANUAL)
        m.setCharging(ChargerControl(ChargerMode.SOLAR, 16, true)) // solar is meaningless in MANUAL -> fixed 16A
        m.tick()
        coVerify { ch.apply(ChargerCommand.Charge(Ampere(16))) }
        coVerify(exactly = 0) { bat.setChargingPower(any()) }
    }

    @Test fun `stop forces zero in MANUAL`() = runTest {
        val ch = charger(0)
        val world = World(grid(-3000), ch, emptyMap(), heatpump(0), battery(0))
        val m = manager(world)
        m.setMode(ManagerMode.MANUAL)
        m.setCharging(ChargerControl(charging = false))
        m.tick()
        coVerify { ch.apply(ChargerCommand.Stop) }
    }

    @Test fun `no car connected forces charger to zero despite surplus`() = runTest {
        val ch = mockk<Charger>(relaxed = true).also {
            coEvery { it.getState() } returns DeviceUpdate(
                GlobalTimeSource.source.markNow(),
                ChargerState(Watt(0), ChargerConnection.NotConnected)
            )
        }
        val world = World(grid(-5000), ch, emptyMap(), heatpump(0), battery(0))
        manager(world).tick()
        coVerify { ch.apply(ChargerCommand.Stop) }
    }

    @Test fun `stale grid reading is treated as missing and triggers blind release`() = runTest {
        val ts = TestTimeSource()
        val staleMark = ts.markNow()
        ts += 60.seconds // reading is now 60s old — well past STALE_AFTER
        val g = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returns DeviceUpdate(staleMark, GridState(Watt(0), Volt(230u)))
        }
        val bat = battery(0)
        val world = World(g, null, emptyMap(), null, bat)
        val m = manager(world)
        repeat(EnergyManager.BLIND_RELEASE_TICKS) { m.tick() }
        coVerify(exactly = 1) { bat.releaseToInverter() }
    }

    @Test fun `reading just under the staleness threshold is still used`() = runTest {
        val ts = TestTimeSource()
        val freshMark = ts.markNow()
        ts += 10.seconds // under the 15s threshold
        val g = mockk<Grid>().also {
            coEvery { it.update() } just runs
            coEvery { it.getState() } returns DeviceUpdate(freshMark, GridState(Watt(600), Volt(230u)))
        }
        val bat = battery(200)
        val world = World(g, null, emptyMap(), null, bat)
        manager(world).tick()
        // Degraded tier ran on the (fresh) reading: decideDegraded(600, 200) = 200 - 600 = -400
        coVerify { bat.setChargingPower(Watt(-400)) }
    }

    @Test fun `loadChargerControl restores persisted fixed amps`() = runTest {
        val store = FakeChargerControlStore()
        store.put("c", "FIXED", 10, true) // config() charger name is "c"
        val ch = charger(0)
        val world = World(grid(0), ch, emptyMap(), heatpump(0), battery(0))
        val m = EnergyManager(world, config(), SurplusPriorityStrategy(), store)
        m.loadChargerControl()
        m.tick()
        coVerify { ch.apply(ChargerCommand.Charge(Ampere(10))) }
    }
}
