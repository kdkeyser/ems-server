package io.konektis.ems

import io.konektis.ems.ui.components.PowerSign
import io.konektis.ems.ui.components.flowSign
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.components.powerSign
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerFormatTest {

    @Test fun `formatWatts null is em dash`() = assertEquals("—", formatWatts(null))

    @Test fun `formatWatts under 1000 shows watts`() = assertEquals("600 W", formatWatts(600))

    @Test fun `formatWatts negative uses magnitude`() = assertEquals("800 W", formatWatts(-800))

    @Test fun `formatWatts 1000 and over shows kilowatts`() {
        assertEquals("3.2 kW", formatWatts(3200))
        assertEquals("1.0 kW", formatWatts(1000))
    }

    @Test fun `powerSign zero or null is idle`() {
        assertEquals(PowerSign.IDLE, powerSign(null, positiveIsConsumption = true))
        assertEquals(PowerSign.IDLE, powerSign(0, positiveIsConsumption = true))
    }

    @Test fun `powerSign solar positive produces`() {
        // solar reports positive when producing; positiveIsConsumption = false
        assertEquals(PowerSign.PRODUCING, powerSign(3200, positiveIsConsumption = false))
    }

    @Test fun `powerSign grid import consumes, export produces`() {
        assertEquals(PowerSign.CONSUMING, powerSign(600, positiveIsConsumption = true))
        assertEquals(PowerSign.PRODUCING, powerSign(-600, positiveIsConsumption = true))
    }

    @Test fun `flowSign maps direction and favorability`() {
        assertEquals(PowerSign.IDLE, flowSign(io.konektis.ems.ui.components.FlowDirection.NONE, forwardIsFavorable = true))
        assertEquals(PowerSign.PRODUCING, flowSign(io.konektis.ems.ui.components.FlowDirection.FORWARD, forwardIsFavorable = true))
        assertEquals(PowerSign.CONSUMING, flowSign(io.konektis.ems.ui.components.FlowDirection.FORWARD, forwardIsFavorable = false))
        assertEquals(PowerSign.CONSUMING, flowSign(io.konektis.ems.ui.components.FlowDirection.REVERSE, forwardIsFavorable = true))
    }
}
