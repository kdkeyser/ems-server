package io.konektis.ems.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.StatusState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    statusFlow: Flow<StatusState>,
    val controlState: StateFlow<ControlState>,
    private val sendCommand: suspend (ClientMessage) -> Unit
) : ViewModel() {

    val statusState: StateFlow<StatusState?> = statusFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    fun setCharging(state: ChargingState) {
        viewModelScope.launch {
            sendCommand(ClientMessage.SetCharging(state))
        }
    }
}
