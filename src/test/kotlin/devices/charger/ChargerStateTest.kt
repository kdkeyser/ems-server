package io.konektis.devices.charger

import io.konektis.devices.Watt
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerStateTest {
    @Test
    fun `connection defaults to Unknown`() {
        assertEquals(ChargerConnection.Unknown, ChargerState(Watt(0)).connection)
    }
}
