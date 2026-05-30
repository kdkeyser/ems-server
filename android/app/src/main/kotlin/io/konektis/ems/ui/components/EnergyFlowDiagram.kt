package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

// Fractional tile-CENTER positions within the diagram box.
// Grid – House – Battery share one horizontal row; Solar sits above House.
private const val SOLAR_X = 0.50f;   private const val SOLAR_Y = 0.18f
private const val HOUSE_X = 0.50f;   private const val ROW_Y = 0.64f
private const val GRID_X = 0.17f
private const val BATTERY_X = 0.83f

// Rounded tile holds icon + name. House is a touch larger.
private val NODE_W = 86.dp;  private val NODE_H = 74.dp
private val HOUSE_W = 96.dp; private val HOUSE_H = 82.dp
private val VALUE_BLOCK = 22.dp   // power value text below the tile
private val LABEL_GAP = 4.dp      // tile bottom → power value
private val EDGE_GAP = 8.dp       // gap between an arrow end and a tile

/**
 * SMA-style energy flow. Each node is a rounded tile containing its icon + name,
 * with the power value shown below the tile. Arrows are thin solid lines with a
 * filled triangle tip, connecting tile centers and inset clear of the tiles (and,
 * for the vertical solar arrow, clear of solar's value text).
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

            val nodeHalfH = NODE_H.toPx() / 2f
            val nodeHalfW = NODE_W.toPx() / 2f
            val houseHalfH = HOUSE_H.toPx() / 2f
            val houseHalfW = HOUSE_W.toPx() / 2f
            val gap = EDGE_GAP.toPx()
            val valueBlock = VALUE_BLOCK.toPx() + LABEL_GAP.toPx()

            // Solar arrow is vertical: start below solar's value text, end above house tile.
            drawFlow(solar, house, solarDir, solarColor,
                fromRadius = nodeHalfH + valueBlock + gap, toRadius = houseHalfH + gap)
            // Grid/Battery arrows are horizontal: inset by tile half-widths.
            drawFlow(grid, house, gridDir, gridColor,
                fromRadius = nodeHalfW + gap, toRadius = houseHalfW + gap)
            drawFlow(house, battery, batteryDir, batteryColor,
                fromRadius = houseHalfW + gap, toRadius = nodeHalfW + gap)
        }

        // Tiles (icon + name inside) centered on their anchors.
        NodeTile(EmsIcons.Solar, "Solar", NODE_W, NODE_H, ems.tileBg, SOLAR_X, SOLAR_Y, wDp, hDp)
        NodeTile(EmsIcons.House, "Home", HOUSE_W, HOUSE_H, ems.houseTileBg, HOUSE_X, ROW_Y, wDp, hDp)
        NodeTile(EmsIcons.Grid,
            if ((state?.gridW ?: 0) < 0) "Grid · export" else "Grid · import",
            NODE_W, NODE_H, ems.tileBg, GRID_X, ROW_Y, wDp, hDp)
        NodeTile(EmsIcons.Battery,
            state?.batteryCharge?.let { "Battery · $it%" } ?: "Battery",
            NODE_W, NODE_H, ems.tileBg, BATTERY_X, ROW_Y, wDp, hDp)

        // Power values, below each tile.
        ValueLabel(formatWatts(state?.totalSolarW),
            powerColor(powerSign(state?.totalSolarW, positiveIsConsumption = false)),
            SOLAR_X, SOLAR_Y, NODE_H, wDp, hDp)
        ValueLabel(
            if (state == null) "—" else formatWatts(houseLoadW(state.totalSolarW, state.gridW, state.batteryW)),
            ems.onTile, HOUSE_X, ROW_Y, HOUSE_H, wDp, hDp)
        ValueLabel(formatWatts(state?.gridW),
            powerColor(powerSign(state?.gridW, positiveIsConsumption = true)),
            GRID_X, ROW_Y, NODE_H, wDp, hDp)
        ValueLabel(formatWatts(state?.batteryW),
            powerColor(powerSign(state?.batteryW, positiveIsConsumption = true)),
            BATTERY_X, ROW_Y, NODE_H, wDp, hDp)
    }
}

@Composable
private fun NodeTile(
    icon: ImageVector,
    caption: String,
    tileW: Dp,
    tileH: Dp,
    tileColor: Color,
    fx: Float,
    fy: Float,
    w: Dp,
    h: Dp,
) {
    val ems = LocalEmsColors.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tileColor,
        modifier = Modifier
            .offset(x = w * fx - tileW / 2, y = h * fy - tileH / 2)
            .size(tileW, tileH),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = caption,
                tint = ems.onTile,
                modifier = Modifier.size(28.dp),
            )
            Text(
                caption,
                color = ems.idle,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).width(tileW - 8.dp),
            )
        }
    }
}

@Composable
private fun ValueLabel(
    value: String,
    valueColor: Color,
    fx: Float,
    fy: Float,
    tileH: Dp,
    w: Dp,
    h: Dp,
) {
    Text(
        value,
        color = valueColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 16.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .offset(x = w * fx - 60.dp, y = h * fy + tileH / 2 + LABEL_GAP)
            .width(120.dp),
    )
}

/** Draws one thin, solid arrow connecting two tile centers, inset to each end, filled tip. */
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
    val headLen = 11.dp.toPx()
    val headHalfWidth = 6.dp.toPx()
    val headAngle = atan2(end.y - start.y, end.x - start.x)
    val shaftEnd = Offset(end.x - cos(headAngle) * headLen, end.y - sin(headAngle) * headLen)
    drawLine(color, start, shaftEnd, stroke, cap = StrokeCap.Round)

    val perpX = -sin(headAngle)
    val perpY = cos(headAngle)
    val baseLeft = Offset(shaftEnd.x + perpX * headHalfWidth, shaftEnd.y + perpY * headHalfWidth)
    val baseRight = Offset(shaftEnd.x - perpX * headHalfWidth, shaftEnd.y - perpY * headHalfWidth)
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo(baseLeft.x, baseLeft.y)
        lineTo(baseRight.x, baseRight.y)
        close()
    }
    drawPath(head, color)
}
