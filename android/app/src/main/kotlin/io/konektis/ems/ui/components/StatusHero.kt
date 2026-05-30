package io.konektis.ems.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.ui.theme.LocalEmsColors

/** Large centered status card: icon tile, big value, and a status line with a colored dot. */
@Composable
fun StatusHero(
    icon: ImageVector,
    value: String,
    valueColor: Color,
    statusText: String,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconTile(icon = icon, contentDescription = statusText, size = 60.dp, cornerRadius = 18.dp)
            Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
