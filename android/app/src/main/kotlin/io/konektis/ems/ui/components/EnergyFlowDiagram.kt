package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.theme.LocalEmsColors
import io.konektis.ems.ui.theme.powerColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Fractional icon-CENTER positions within the diagram box.
// Grid – House – Battery share one horizontal row; Solar sits above House.
private const val SOLAR_X = 0.50f;   private const val SOLAR_Y = 0.16f
private const val HOUSE_X = 0.50f;   private const val ROW_Y = 0.66f
private const val GRID_X = 0.16f
private const val BATTERY_X = 0.84f

private val NODE_TILE = 52.dp
private val HOUSE_TILE = 66.dp
private val LABEL_WIDTH = 120.dp
private val LABEL_GAP = 4.dp        // icon edge → start of its label block
private val LABEL_BLOCK = 36.dp     // caption + value stacked
private val EDGE_GAP = 6.dp         // gap between an arrow end and an icon edge

/**
 * SMA-style energy flow. Each node stacks icon → label → power value (all below the
 * icon). Arrows are thin, solid, color-coded and connect icon-center to icon-center,
 * inset to the tile edge. The solar arrow starts below solar's value text so it never
 * clips the label.
 */
@Composable
fun EnergyFlowDiagram(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current

    val solarDir = flowDirection(state?.totalSolarW, positiveIsForward = true)
    val gridDir = flowDirection(state?.gridW, positiveIsForward = true)
    val batteryDir = flowDirection(state?.batteryW, positiveIsForward = true)
    val solarColor = powerColor(flowSign(solarDir, forwardIsFavorable = true))
    val gridColor = powerColor(flowSign(gridDir, forwardIsFavorable = false))
    val batteryColor = powerColor(flowSign(batteryDir, forwardIsFavorable = false))

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val wDp = maxWidth
        val hDp = maxHeight

        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val solar = Offset(SOLAR_X * w, SOLAR_Y * h)
            val house = Offset(HOUSE_X * w, ROW_Y * h)
            val grid = Offset(GRID_X * w, ROW_Y * h)
            val battery = Offset(BATTERY_X * w, ROW_Y * h)
            val rNode = NODE_TILE.toPx() / 2f + EDGE_GAP.toPx()
            val rHouse = HOUSE_TILE.toPx() / 2f + EDGE_GAP.toPx()
            // Solar's arrow begins below its value text, not at the tile edge.
            val rSolarStart = NODE_TILE.toPx() / 2f + LABEL_GAP.toPx() + LABEL_BLOCK.toPx() + EDGE_GAP.toPx()
            drawFlow(solar, house, solarDir, solarColor, rSolarStart, rHouse)
            drawFlow(grid, house, gridDir, gridColor, rNode, rHouse)
            drawFlow(house, battery, batteryDir, batteryColor, rHouse, rNode)
        }

        // Icon tiles — each centered exactly on its anchor.
        IconNode(EmsIcons.Solar, NODE_TILE, ems.tileBg, SOLAR_X, SOLAR_Y, wDp, hDp)
        IconNode(EmsIcons.House, HOUSE_TILE, ems.houseTileBg, HOUSE_X, ROW_Y, wDp, hDp)
        IconNode(EmsIcons.Grid, NODE_TILE, ems.tileBg, GRID_X, ROW_Y, wDp, hDp)
        IconNode(EmsIcons.Battery, NODE_TILE, ems.tileBg, BATTERY_X, ROW_Y, wDp, hDp)

        // Labels — all below their icon: caption first, then the power value.
        Label(
            caption = "Solar",
            value = formatWatts(state?.totalSolarW),
            valueColor = powerColor(powerSign(state?.totalSolarW, positiveIsConsumption = false)),
            fx = SOLAR_X, fy = SOLAR_Y, tile = NODE_TILE, w = wDp, h = hDp,
        )
        Label(
            caption = "Home",
            value = if (state == null) "—"
                    else formatWatts(houseLoadW(state.totalSolarW, state.gridW, state.batteryW)),
            valueColor = ems.onTile,
            fx = HOUSE_X, fy = ROW_Y, tile = HOUSE_TILE, w = wDp, h = hDp,
        )
        Label(
            caption = if ((state?.gridW ?: 0) < 0) "Grid · export" else "Grid · import",
            value = formatWatts(state?.gridW),
            valueColor = powerColor(powerSign(state?.gridW, positiveIsConsumption = true)),
            fx = GRID_X, fy = ROW_Y, tile = NODE_TILE, w = wDp, h = hDp,
        )
        Label(
            caption = state?.batteryCharge?.let { "Battery · $it%" } ?: "Battery",
            value = formatWatts(state?.batteryW),
            valueColor = powerColor(powerSign(state?.batteryW, positiveIsConsumption = true)),
            fx = BATTERY_X, fy = ROW_Y, tile = NODE_TILE, w = wDp, h = hDp,
        )
    }
}

@Composable
private fun IconNode(
    icon: ImageVector,
    tile: Dp,
    tileColor: Color,
    fx: Float,
    fy: Float,
    w: Dp,
    h: Dp,
) {
    IconTile(
        icon = icon,
        contentDescription = null,
        size = tile,
        tileColor = tileColor,
        modifier = Modifier.offset(x = w * fx - tile / 2, y = h * fy - tile / 2),
    )
}

@Composable
private fun Label(
    caption: String,
    value: String,
    valueColor: Color,
    fx: Float,
    fy: Float,
    tile: Dp,
    w: Dp,
    h: Dp,
) {
    val ems = LocalEmsColors.current
    Column(
        modifier = Modifier
            .offset(x = w * fx - LABEL_WIDTH / 2, y = h * fy + tile / 2 + LABEL_GAP)
            .width(LABEL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            caption, color = ems.idle, fontSize = 10.sp,
            maxLines = 1, textAlign = TextAlign.Center,
        )
        Text(
            value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

/** Draws one thin, solid arrow connecting two icon centers, inset to each end. */
private fun DrawScope.drawFlow(
    from: Offset,
    to: Offset,
    dir: FlowDirection,
    color: Color,
    fromRadius: Float,
    toRadius: Float,
) {
    if (dir == FlowDirection.NONE) return
    val angle = atan2(to.y - from.y, to.x - from.x)
    val a = Offset(from.x + cos(angle) * fromRadius, from.y + sin(angle) * fromRadius)
    val b = Offset(to.x - cos(angle) * toRadius, to.y - sin(angle) * toRadius)
    val start = if (dir == FlowDirection.FORWARD) a else b
    val end = if (dir == FlowDirection.FORWARD) b else a
    val stroke = 2.dp.toPx()
    drawLine(color, start, end, stroke)
    val headAngle = atan2(end.y - start.y, end.x - start.x)
    val headLen = 10.dp.toPx()
    val spread = 0.5f
    drawLine(color, end, Offset(end.x - cos(headAngle - spread) * headLen, end.y - sin(headAngle - spread) * headLen), stroke)
    drawLine(color, end, Offset(end.x - cos(headAngle + spread) * headLen, end.y - sin(headAngle + spread) * headLen), stroke)
}
