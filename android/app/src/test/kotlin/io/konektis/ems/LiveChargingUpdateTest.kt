package io.konektis.ems

import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ChargerMode
import io.konektis.ems.ui.charger.liveChargingUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveChargingUpdateTest {
    @Test fun `active session pushes the edit live, keeping it on`() {
        assertEquals(
            ChargerControl(mode = ChargerMode.FIXED, fixedAmps = 20, charging = true),
            liveChargingUpdate(sessionActive = true, mode = ChargerMode.FIXED, fixedAmps = 20),
        )
        assertEquals(
            ChargerControl(mode = ChargerMode.SOLAR, fixedAmps = 16, charging = true),
            liveChargingUpdate(sessionActive = true, mode = ChargerMode.SOLAR, fixedAmps = 16),
        )
    }

    @Test fun `idle session stages only - nothing is sent`() {
        assertNull(liveChargingUpdate(sessionActive = false, mode = ChargerMode.FIXED, fixedAmps = 20))
        assertNull(liveChargingUpdate(sessionActive = false, mode = ChargerMode.SOLAR, fixedAmps = 16))
    }
}
