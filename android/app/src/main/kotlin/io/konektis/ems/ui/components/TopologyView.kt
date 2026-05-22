package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.konektis.ems.data.model.StatusState

fun lineColor(w: Int?, positiveIsConsumption: Boolean): Color {
    if (w == null || w == 0) return Color(0xFF4B5563)
    return if (positiveIsConsumption == (w > 0)) colorRed else colorGreen
}

private data class NodePos(val x: Float, val y: Float) {
    fun toBias() = BiasAlignment(x * 2f - 1f, y * 2f - 1f)
}

private val POS_SOLAR    = NodePos(0.50f, 0.14f)
private val POS_GRID     = NodePos(0.12f, 0.50f)
private val POS_HOUSE    = NodePos(0.50f, 0.50f)
private val POS_BATTERY  = NodePos(0.88f, 0.50f)
private val POS_CHARGER  = NodePos(0.30f, 0.84f)
private val POS_HEATPUMP = NodePos(0.70f, 0.84f)

@Composable
fun TopologyView(state: StatusState?, modifier: Modifier = Modifier) {
    val cats = state?.devices?.groupBy { it.category } ?: emptyMap()

    Box(modifier = modifier.fillMaxWidth().aspectRatio(5f / 3f)) {

        Canvas(modifier = Modifier.matchParentSize()) {
            val hw = size.width * POS_HOUSE.x
            val hy = size.height * POS_HOUSE.y
            fun line(pos: NodePos, color: Color) = drawLine(
                color = color,
                start = Offset(size.width * pos.x, size.height * pos.y),
                end   = Offset(hw, hy),
                strokeWidth = 2.dp.toPx()
            )
            if (cats["solar"]    != null) line(POS_SOLAR,    lineColor(state?.totalSolarW, false))
            if (cats["grid"]     != null) line(POS_GRID,     lineColor(state?.gridW,       true))
            if (cats["battery"]  != null) line(POS_BATTERY,  lineColor(state?.batteryW,    true))
            if (cats["charger"]  != null) line(POS_CHARGER,  lineColor(state?.chargerW,    true))
            if (cats["heatpump"] != null) line(POS_HEATPUMP, lineColor(state?.heatpumpW,   true))
        }

        DeviceNode(
            icon = "🏠", label = "House", powerW = null,
            positiveIsConsumption = true, devices = emptyList(), isHouse = true,
            modifier = Modifier.align(POS_HOUSE.toBias())
        )

        if (cats["solar"] != null) DeviceNode(
            icon = "☀️", label = "Solar",
            powerW = state?.totalSolarW, positiveIsConsumption = false,
            devices = cats["solar"]!!, modifier = Modifier.align(POS_SOLAR.toBias())
        )
        if (cats["grid"] != null) DeviceNode(
            icon = "🔌", label = "Grid",
            powerW = state?.gridW, positiveIsConsumption = true,
            devices = cats["grid"]!!, modifier = Modifier.align(POS_GRID.toBias())
        )
        if (cats["battery"] != null) DeviceNode(
            icon = "🔋", label = "Battery",
            powerW = state?.batteryW, positiveIsConsumption = true,
            devices = cats["battery"]!!,
            extraText = state?.batteryCharge?.let { "$it% SoC" },
            modifier = Modifier.align(POS_BATTERY.toBias())
        )
        if (cats["charger"] != null) DeviceNode(
            icon = "🚗", label = "Charger",
            powerW = state?.chargerW, positiveIsConsumption = true,
            devices = cats["charger"]!!, modifier = Modifier.align(POS_CHARGER.toBias())
        )
        if (cats["heatpump"] != null) DeviceNode(
            icon = "🌡️", label = "Heat Pump",
            powerW = state?.heatpumpW, positiveIsConsumption = true,
            devices = cats["heatpump"]!!, modifier = Modifier.align(POS_HEATPUMP.toBias())
        )
    }
}
