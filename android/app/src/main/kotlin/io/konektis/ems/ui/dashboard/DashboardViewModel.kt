package io.konektis.ems.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    statusFlow: Flow<StatusState>,
    val controlState: StateFlow<ControlState>,
    val mode: StateFlow<ManagerMode?> = MutableStateFlow(null),
    val chargerControl: StateFlow<ChargerControl?> = MutableStateFlow(null),
    val carSoc: StateFlow<Int?> = MutableStateFlow(null),
    private val sendCommand: suspend (ClientMessage) -> Unit
) : ViewModel() {

    val statusState: StateFlow<StatusState?> = statusFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    fun setCharging(control: ChargerControl) {
        viewModelScope.launch {
            sendCommand(ClientMessage.SetCharging(control))
        }
    }

    fun setMode(mode: ManagerMode) {
        viewModelScope.launch {
            sendCommand(ClientMessage.SetMode(mode))
        }
    }
}
