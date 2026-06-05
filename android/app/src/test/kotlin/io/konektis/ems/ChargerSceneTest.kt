package io.konektis.ems

import io.konektis.ems.ui.charger.BoltMode
import io.konektis.ems.ui.charger.ChargerSceneSpec
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerSceneSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerSceneTest {
    @Test fun `maps ui state and session to scene spec`() {
        assertEquals(ChargerSceneSpec(true, true, BoltMode.OFF), chargerSceneSpec(ChargerUiState.NO_CAR, sessionActive = false))
        assertEquals(ChargerSceneSpec(true, true, BoltMode.OFF), chargerSceneSpec(ChargerUiState.NO_CAR, sessionActive = true))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.OFF), chargerSceneSpec(ChargerUiState.CONNECTED_IDLE, sessionActive = false))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.ARMED), chargerSceneSpec(ChargerUiState.CONNECTED_IDLE, sessionActive = true))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.CHARGING), chargerSceneSpec(ChargerUiState.CHARGING, sessionActive = true))
        assertEquals(ChargerSceneSpec(false, false, BoltMode.OFF), chargerSceneSpec(ChargerUiState.CONTROLS_FALLBACK, sessionActive = true))
    }
}
