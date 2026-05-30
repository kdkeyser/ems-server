package io.konektis.ems.ui.components

import kotlin.math.abs

/** Formats watts: null → "—", < 1000 → "600 W", ≥ 1000 → "3.2 kW". Uses magnitude. */
fun formatWatts(w: Int?): String {
    if (w == null) return "—"
    val a = abs(w)
    return if (a >= 1000) "${"%.1f".format(a / 1000f)} kW" else "$a W"
}

/** Whether a device's power reads as producing, consuming, or idle. */
enum class PowerSign { PRODUCING, CONSUMING, IDLE }

/**
 * @param positiveIsConsumption true for grid/battery (positive = importing/charging),
 *        false for solar (positive = producing).
 */
fun powerSign(w: Int?, positiveIsConsumption: Boolean): PowerSign {
    if (w == null || w == 0) return PowerSign.IDLE
    val consuming = positiveIsConsumption == (w > 0)
    return if (consuming) PowerSign.CONSUMING else PowerSign.PRODUCING
}

/** Direction a flow arrow points along a from→to segment. */
enum class FlowDirection { FORWARD, REVERSE, NONE }

/** Maps a drawn flow direction to a PowerSign for coloring, given whether forward is the favorable direction. */
fun flowSign(direction: FlowDirection, forwardIsFavorable: Boolean): PowerSign = when (direction) {
    FlowDirection.NONE -> PowerSign.IDLE
    FlowDirection.FORWARD -> if (forwardIsFavorable) PowerSign.PRODUCING else PowerSign.CONSUMING
    FlowDirection.REVERSE -> if (forwardIsFavorable) PowerSign.CONSUMING else PowerSign.PRODUCING
}
