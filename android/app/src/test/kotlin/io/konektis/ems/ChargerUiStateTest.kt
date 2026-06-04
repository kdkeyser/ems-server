package io.konektis.ems

import io.konektis.ems.data.model.ChargerConnection
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargerUiStateTest {
    @Test fun `maps connection to ui state`() {
        assertEquals(ChargerUiState.NO_CAR, chargerUiState(ChargerConnection.NotConnected))
        assertEquals(ChargerUiState.CONNECTED_IDLE, chargerUiState(ChargerConnection.Connected))
        assertEquals(ChargerUiState.CHARGING, chargerUiState(ChargerConnection.Charging))
        assertEquals(ChargerUiState.CONTROLS_FALLBACK, chargerUiState(ChargerConnection.Unknown))
        assertEquals(ChargerUiState.CONTROLS_FALLBACK, chargerUiState(null))
    }

    @Test fun `parses connection names and tolerates junk`() {
        assertEquals(ChargerConnection.Charging, parseChargerConnection("Charging"))
        assertNull(parseChargerConnection("bogus"))
        assertNull(parseChargerConnection(null))
    }
}
