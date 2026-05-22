package io.konektis.ems.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus

val colorGreen = Color(0xFF34D399)
val colorRed   = Color(0xFFF87171)
val colorGray  = Color(0xFF9CA3AF)

fun powerColor(w: Int?, positiveIsConsumption: Boolean): Color {
    if (w == null || w == 0) return colorGray
    return if (positiveIsConsumption == (w > 0)) colorRed else colorGreen
}

fun fmtWatts(w: Int?): String {
    if (w == null) return "—"
    val a = Math.abs(w)
    return if (a >= 1000) "${"%.1f".format(a / 1000f)} kW" else "$a W"
}

@Composable
fun DeviceNode(
    icon: String,
    label: String,
    powerW: Int?,
    positiveIsConsumption: Boolean,
    devices: List<DeviceStatus>,
    extraText: String? = null,
    isHouse: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isHouse) Color(0xFF3B82F6) else Color(0xFF4B5563)

    Column(
        modifier = modifier
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = if (isHouse) 14.dp else 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(icon, fontSize = 22.sp)
        Text(label, fontSize = 10.sp, color = colorGray)
        if (!isHouse) {
            Text(
                fmtWatts(powerW),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = powerColor(powerW, positiveIsConsumption)
            )
            if (extraText != null) {
                Text(extraText, fontSize = 10.sp, color = colorGray)
            }
            if (devices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    devices.forEach { d ->
                        val dot = if (d.health is DeviceHealth.Online) Color(0xFF4ADE80) else colorRed
                        Text("●", fontSize = 8.sp, color = dot)
                    }
                }
            }
        }
    }
}
