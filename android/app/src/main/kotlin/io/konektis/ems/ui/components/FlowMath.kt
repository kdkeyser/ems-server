package io.konektis.ems.ui.components

import kotlin.math.hypot

/** Plain 2D point, framework-free so it is unit-testable. */
data class Pt(val x: Float, val y: Float)

/**
 * Returns the sub-segment of from→to, shortened by [startInset] at the start
 * and [endInset] at the end. Used to keep arrows clear of the icons at each node.
 */
fun insetSegment(from: Pt, to: Pt, startInset: Float, endInset: Float): Pair<Pt, Pt> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len == 0f) return from to to
    val ux = dx / len
    val uy = dy / len
    val s = Pt(from.x + ux * startInset, from.y + uy * startInset)
    val e = Pt(to.x - ux * endInset, to.y - uy * endInset)
    return s to e
}

/** Battery state-of-charge as a 0f..1f fraction, clamped. null → 0f. */
fun socFraction(percent: Int?): Float {
    if (percent == null) return 0f
    return percent.coerceIn(0, 100) / 100f
}

/**
 * Direction of flow along a from→to segment.
 * @param positiveIsForward true when a positive power value means flow runs from→to.
 */
fun flowDirection(powerW: Int?, positiveIsForward: Boolean): FlowDirection {
    if (powerW == null || powerW == 0) return FlowDirection.NONE
    val forward = positiveIsForward == (powerW > 0)
    return if (forward) FlowDirection.FORWARD else FlowDirection.REVERSE
}

/**
 * Total power consumed by the house, derived from the energy balance at the house bus.
 *
 * Convention (as observed in this app's StatusState): solar positive = production,
 * grid positive = import, battery positive = charging. Then:
 *   houseLoad = solarProduction + gridImport + batteryDischarge
 *             = solar + grid - battery
 * Nulls are treated as 0. If the solar sign turns out inverted on real hardware,
 * this is the single place to flip it.
 */
fun houseLoadW(solarW: Int?, gridW: Int?, batteryW: Int?): Int =
    (solarW ?: 0) + (gridW ?: 0) - (batteryW ?: 0)
