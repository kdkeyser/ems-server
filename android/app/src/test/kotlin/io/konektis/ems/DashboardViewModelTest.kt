@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.konektis.ems

import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeState(gridW: Int) = StatusState(
        devices = listOf(DeviceStatus("Grid meter", DeviceHealth.Online(0L, gridW), "grid")),
        totalSolarW = null, gridW = gridW, batteryW = null,
        batteryCharge = null, chargerW = null, heatpumpW = null
    )

    @Test
    fun `statusState is null before first emission`() = runTest {
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = MutableStateFlow(ControlState.Connecting),
            sendCommand = {}
        )
        assertNull(vm.statusState.value)
    }

    @Test
    fun `statusState updates when flow emits`() = runTest {
        val state = makeState(-800)
        val vm = DashboardViewModel(
            statusFlow = flowOf(state),
            controlState = MutableStateFlow(ControlState.Connecting),
            sendCommand = {}
        )
        val job = launch { vm.statusState.collect {} }
        yield()
        assertEquals(state, vm.statusState.value)
        job.cancel()
    }

    @Test
    fun `setCharging forwards command to sendCommand`() = runTest {
        val sent = mutableListOf<ClientMessage>()
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = MutableStateFlow(ControlState.Authenticated),
            sendCommand = { sent.add(it) }
        )
        vm.setCharging(ChargingState.NotCharging)
        yield()
        assertEquals(1, sent.size)
        assertEquals(ClientMessage.SetCharging(ChargingState.NotCharging), sent.first())
    }
}
