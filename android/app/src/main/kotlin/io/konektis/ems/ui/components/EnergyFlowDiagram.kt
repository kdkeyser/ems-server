package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.theme.LocalEmsColors
import io.konektis.ems.ui.theme.powerColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private data class NodeAnchor(val x: Float, val y: Float) {
    fun bias() = BiasAlignment(x * 2f - 1f, y * 2f - 1f)
    fun pt(w: Float, h: Float) = Pt(w * x, h * y)
}

private val ANCHOR_SOLAR   = NodeAnchor(0.50f, 0.16f)
private val ANCHOR_HOUSE   = NodeAnchor(0.50f, 0.54f)
private val ANCHOR_GRID    = NodeAnchor(0.16f, 0.86f)
private val ANCHOR_BATTERY = NodeAnchor(0.84f, 0.86f)

/**
 * SMA-style energy flow: nodes carry their own values beneath their icons; the
 * connecting lines are short, thin, solid, color-coded, with a small arrowhead.
 */
@Composable
fun EnergyFlowDiagram(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val density = LocalDensity.current
    val inset = with(density) { 46.dp.toPx() } // keep arrows clear of icons

    val solarDir = flowDirection(state?.totalSolarW, positiveIsForward = true)
    val gridDir = flowDirection(state?.gridW, positiveIsForward = true)
    val batteryDir = flowDirection(state?.batteryW, positiveIsForward = true)
    val solarColor = powerColor(flowSign(solarDir, forwardIsFavorable = true))
    val gridColor = powerColor(flowSign(gridDir, forwardIsFavorable = false))
    val batteryColor = powerColor(flowSign(batteryDir, forwardIsFavorable = false))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val house = ANCHOR_HOUSE.pt(w, h)
            drawFlow(ANCHOR_SOLAR.pt(w, h), house, solarDir, solarColor, inset)
            drawFlow(ANCHOR_GRID.pt(w, h), house, gridDir, gridColor, inset)
            drawFlow(house, ANCHOR_BATTERY.pt(w, h), batteryDir, batteryColor, inset)
        }

        FlowNode(
            icon = EmsIcons.Solar, caption = "Solar",
            value = formatWatts(state?.totalSolarW),
            valueColor = powerColor(powerSign(state?.totalSolarW, positiveIsConsumption = false)),
            modifier = Modifier.align(ANCHOR_SOLAR.bias()),
        )
        FlowNode(
            icon = EmsIcons.House, caption = "Home",
            value = if (state == null) "—"
                    else formatWatts(houseLoadW(state.totalSolarW, state.gridW, state.batteryW)),
            valueColor = ems.onTile,
            big = true, tileColor = ems.houseTileBg,
            modifier = Modifier.align(ANCHOR_HOUSE.bias()),
        )
        FlowNode(
            icon = EmsIcons.Grid,
            caption = if ((state?.gridW ?: 0) < 0) "Grid · export" else "Grid · import",
            value = formatWatts(state?.gridW),
            valueColor = powerColor(powerSign(state?.gridW, positiveIsConsumption = true)),
            modifier = Modifier.align(ANCHOR_GRID.bias()),
        )
        FlowNode(
            icon = EmsIcons.Battery,
            caption = state?.batteryCharge?.let { "Battery · $it%" } ?: "Battery",
            value = formatWatts(state?.batteryW),
            valueColor = powerColor(powerSign(state?.batteryW, positiveIsConsumption = true)),
            modifier = Modifier.align(ANCHOR_BATTERY.bias()),
        )
    }
}

@Composable
private fun FlowNode(
    icon: ImageVector,
    caption: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    tileColor: Color = LocalEmsColors.current.tileBg,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconTile(
            icon = icon,
            contentDescription = caption,
            size = if (big) 64.dp else 50.dp,
            tileColor = tileColor,
        )
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = LocalEmsColors.current.idle, fontSize = 9.sp)
    }
}

/** Draws one short, thin, solid arrow with a small head, oriented by [dir]. */
private fun DrawScope.drawFlow(from: Pt, to: Pt, dir: FlowDirection, color: Color, inset: Float) {
    if (dir == FlowDirection.NONE) return
    val (s, e) = insetSegment(from, to, inset, inset)
    val start = if (dir == FlowDirection.FORWARD) s else e
    val end = if (dir == FlowDirection.FORWARD) e else s
    val stroke = 1.8.dp.toPx()
    val startO = Offset(start.x, start.y)
    val endO = Offset(end.x, end.y)
    drawLine(color, startO, endO, stroke)
    val angle = atan2((endO.y - startO.y), (endO.x - startO.x))
    val headLen = 9.dp.toPx()
    val a = 0.5f
    drawLine(color, endO, Offset(endO.x - cos(angle - a) * headLen, endO.y - sin(angle - a) * headLen), stroke)
    drawLine(color, endO, Offset(endO.x - cos(angle + a) * headLen, endO.y - sin(angle + a) * headLen), stroke)
}
