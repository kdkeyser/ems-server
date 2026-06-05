package io.konektis.ems.ui.charger

/** What the charger scene draws for a given UI state. */
data class ChargerSceneSpec(
    val showCar: Boolean,
    val carDimmed: Boolean,
    val charging: Boolean,
)

fun chargerSceneSpec(uiState: ChargerUiState): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR -> ChargerSceneSpec(showCar = true, carDimmed = true, charging = false)
    ChargerUiState.CONNECTED_IDLE -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = false)
    ChargerUiState.CHARGING -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = true)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, charging = false)
}
