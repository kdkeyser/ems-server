package io.konektis.ems

import io.konektis.ems.ui.charger.ChargerButtonLabel
import io.konektis.ems.ui.charger.chargerButtonState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChargerButtonTest {
    @Test fun `settled button reflects the session`() {
        val start = chargerButtonState(sessionActive = false, pending = null)
        assertEquals(ChargerButtonLabel.START, start.label); assertTrue(start.enabled); assertFalse(start.stopStyle)
        val stop = chargerButtonState(sessionActive = true, pending = null)
        assertEquals(ChargerButtonLabel.STOP, stop.label); assertTrue(stop.enabled); assertTrue(stop.stopStyle)
    }

    @Test fun `pending disables the button and shows progress label`() {
        val starting = chargerButtonState(sessionActive = false, pending = true)
        assertEquals(ChargerButtonLabel.STARTING, starting.label); assertFalse(starting.enabled)
        val stopping = chargerButtonState(sessionActive = true, pending = false)
        assertEquals(ChargerButtonLabel.STOPPING, stopping.label); assertFalse(stopping.enabled)
    }
}
