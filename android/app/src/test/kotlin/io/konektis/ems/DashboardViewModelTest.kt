package io.konektis.ems

import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardViewModelTest {

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
    fun `charger control visible only when Authenticated`() = runTest {
        val controlState = MutableStateFlow<ControlState>(ControlState.Connecting)
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = controlState,
            sendCommand = {}
        )
        assertTrue(!vm.chargerControlVisible.value)
        controlState.value = ControlState.Authenticated
        assertTrue(vm.chargerControlVisible.value)
        controlState.value = ControlState.Unauthenticated
        assertTrue(!vm.chargerControlVisible.value)
    }

    @Test
    fun `setCharging forwards command to sendCommand`() = runTest {
        val sent = mutableListOf<ClientMessage>()
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = MutableStateFlow(ControlState.Authenticated),
            sendCommand = { sent.add(it) }
        )
        vm.setCharging(ChargingState.NotCharging())
        assertEquals(1, sent.size)
        assertEquals(ClientMessage.SetCharging(ChargingState.NotCharging()), sent.first())
    }
}
