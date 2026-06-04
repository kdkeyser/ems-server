package io.konektis.ems.ui.charger

import io.konektis.ems.data.model.ChargerConnection

enum class ChargerUiState { NO_CAR, CONNECTED_IDLE, CHARGING, CONTROLS_FALLBACK }

/** Decides which Charger-tab layout to show. Unknown/absent status keeps the controls visible. */
fun chargerUiState(connection: ChargerConnection?): ChargerUiState = when (connection) {
    ChargerConnection.NotConnected -> ChargerUiState.NO_CAR
    ChargerConnection.Connected -> ChargerUiState.CONNECTED_IDLE
    ChargerConnection.Charging -> ChargerUiState.CHARGING
    ChargerConnection.Unknown, null -> ChargerUiState.CONTROLS_FALLBACK
}
