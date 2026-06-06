package io.konektis.ems

import io.konektis.cardata.CarDataService
import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.charger.Charger
import io.konektis.devices.grid.Grid
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.ocpp.db.ChargerControlStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EnergyManagerCarChargeTest {
    /** A World with no live device data, so buildEMSState's only non-null field comes from CarData. */
    private fun emptyWorld(): World {
        val grid = mockk<Grid>()
        coEvery { grid.getState() } returns null
        return mockk<World> {
            every { this@mockk.grid } returns grid
            every { solar } returns emptyMap<String, Solar>()
            every { batteries } returns emptyMap<String, Battery>()
            every { chargers } returns emptyMap<String, Charger>()
            every { smartConsumers } returns emptyMap<String, SmartConsumer>()
        }
    }

    private fun manager(carService: CarDataService?): EnergyManager {
        val config = mockk<Config>(relaxed = true)
        val strategy = mockk<Strategy>(relaxed = true)
        val store = mockk<ChargerControlStore>(relaxed = true)
        return EnergyManager(emptyWorld(), config, strategy, store, carService)
    }

    @Test
    fun buildEMSStateIncludesCarChargeFromService() = runTest {
        val svc = mockk<CarDataService>()
        every { svc.socFlow } returns MutableStateFlow<Int?>(58)
        assertEquals(58, manager(svc).buildEMSState().carCharge)
    }

    @Test
    fun buildEMSStateCarChargeNullWhenNoService() = runTest {
        assertNull(manager(null).buildEMSState().carCharge)
    }
}
