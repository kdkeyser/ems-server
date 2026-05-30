package io.konektis.ems

import io.konektis.ems.ui.components.FlowDirection
import io.konektis.ems.ui.components.Pt
import io.konektis.ems.ui.components.flowDirection
import io.konektis.ems.ui.components.insetSegment
import io.konektis.ems.ui.components.socFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowMathTest {

    @Test fun `insetSegment shortens a horizontal segment at both ends`() {
        val (s, e) = insetSegment(Pt(0f, 0f), Pt(100f, 0f), startInset = 20f, endInset = 30f)
        assertEquals(20f, s.x, 0.01f); assertEquals(0f, s.y, 0.01f)
        assertEquals(70f, e.x, 0.01f); assertEquals(0f, e.y, 0.01f)
    }

    @Test fun `insetSegment on zero-length segment returns endpoints unchanged`() {
        val (s, e) = insetSegment(Pt(5f, 5f), Pt(5f, 5f), 10f, 10f)
        assertEquals(5f, s.x, 0.01f); assertEquals(5f, e.y, 0.01f)
    }

    @Test fun `socFraction clamps and normalizes`() {
        assertEquals(0f, socFraction(null), 0.001f)
        assertEquals(0f, socFraction(-5), 0.001f)
        assertEquals(0.62f, socFraction(62), 0.001f)
        assertEquals(1f, socFraction(150), 0.001f)
    }

    @Test fun `flowDirection none when zero or null`() {
        assertEquals(FlowDirection.NONE, flowDirection(null, positiveIsForward = true))
        assertEquals(FlowDirection.NONE, flowDirection(0, positiveIsForward = true))
    }

    @Test fun `flowDirection forward and reverse by sign`() {
        assertEquals(FlowDirection.FORWARD, flowDirection(600, positiveIsForward = true))
        assertEquals(FlowDirection.REVERSE, flowDirection(-600, positiveIsForward = true))
        // solar: positive means producing → solar→house is forward, so positiveIsForward = true here too
        assertEquals(FlowDirection.FORWARD, flowDirection(3200, positiveIsForward = true))
    }

    @Test fun `houseLoadW sums production import and discharge, nulls as zero`() {
        // convention: solar positive = production, grid positive = import, battery positive = charging
        // load = solar + grid - battery
        assertEquals(0, io.konektis.ems.ui.components.houseLoadW(null, null, null))
        assertEquals(3800, io.konektis.ems.ui.components.houseLoadW(3200, 600, 0))
        // battery charging (positive) reduces what the house itself draws
        assertEquals(2700, io.konektis.ems.ui.components.houseLoadW(3200, 600, 1100))
        // battery discharging (negative) adds to house supply
        assertEquals(4900, io.konektis.ems.ui.components.houseLoadW(3200, 600, -1100))
    }
}
