package io.konektis.ems

import io.konektis.GlobalTimeSource
import io.konektis.config.loadConfig
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.charger.OcppCharger
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppEmsIntegrationTest {

    @Test
    fun surplusDrivesSetChargingProfileOnOcppCharger() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        every { svc.latestPowerW("CP1", 1) } returns 0   // charger currently drawing 0 W
        every { svc.connectorStatus("CP1", 1) } returns null
        coEvery { svc.getChargerSettings("CP1") } returns null
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        val grid = mockk<Grid>(relaxed = true)
        coEvery { grid.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(-4000), Volt(230u))) // 4 kW export

        val battery = mockk<Battery>(relaxed = true)
        coEvery { battery.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(50u, Watt(0)))

        val heatpump = mockk<SmartConsumer>(relaxed = true)
        coEvery { heatpump.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), SmartConsumerState(Watt(0), ConsumeMode.Unrestricted))

        val world = World(
            grid = grid,
            chargers = mapOf("ocpp" to charger),
            solar = emptyMap(),
            smartConsumers = mapOf("hp" to heatpump),
            batteries = mapOf("bat" to battery),
        )
        // config.yaml's first charger entry bounds amps to [6, 32].
        val manager = EnergyManager(world, loadConfig("/config.yaml"), SurplusPriorityStrategy(), FakeChargerControlStore())

        manager.tick()

        // available = chargerP(0) + batteryP(0) - gridP(-4000) = 4000 W -> 4000/230 = 17 A (within [6,32]).
        coVerify { svc.setChargingProfile("CP1", 1, 17.0, ChargingRateUnitType.A) }
    }
}
