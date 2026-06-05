package io.konektis.ems

import io.konektis.ems.ui.charger.ChargerSceneSpec
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerSceneSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerSceneTest {
    @Test fun `maps ui state to scene spec`() {
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = true, charging = false),
            chargerSceneSpec(ChargerUiState.NO_CAR))
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = false, charging = false),
            chargerSceneSpec(ChargerUiState.CONNECTED_IDLE))
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = false, charging = true),
            chargerSceneSpec(ChargerUiState.CHARGING))
        assertEquals(ChargerSceneSpec(showCar = false, carDimmed = false, charging = false),
            chargerSceneSpec(ChargerUiState.CONTROLS_FALLBACK))
    }
}
