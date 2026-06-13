package io.konektis.ems

import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.devices.grid.Grid
import io.konektis.devices.solar.Solar
import io.konektis.ocpp.db.ChargerControlStore
import io.konektis.history.TimestampedEmsState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EnergyManagerHistoryTapTest {
    private fun emptyWorld(): World {
        val grid = mockk<Grid>()
        coEvery { grid.getState() } returns null
        return mockk<World> {
            every { this@mockk.grid } returns grid
            every { solar } returns emptyMap<String, Solar>()
            every { battery } returns null
            every { charger } returns null
            every { heatPump } returns null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun tickEmitsEveryTickEvenWhenStateUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val config = mockk<Config>(relaxed = true)
        val strategy = mockk<Strategy>(relaxed = true)
        val store = mockk<ChargerControlStore>(relaxed = true)
        val em = EnergyManager(emptyWorld(), config, strategy, store, null)

        val seen = mutableListOf<TimestampedEmsState>()
        val collector = launch { em.emsHistoryFlow.collect { seen.add(it) } }

        em.tick()   // empty world -> identical EMSState each time
        em.tick()
        // give the collector a chance to run on the test dispatcher
        kotlinx.coroutines.yield()

        assertEquals(2, seen.size, "both identical ticks must reach the history flow")
        collector.cancel()
    }
}
