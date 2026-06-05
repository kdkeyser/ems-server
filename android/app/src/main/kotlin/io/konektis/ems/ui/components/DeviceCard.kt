package io.konektis.ems.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.R
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.ui.theme.LocalEmsColors
import io.konektis.ems.ui.theme.powerColor

@Composable
fun DeviceCard(device: DeviceStatus, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val health = device.health
    val online = health is DeviceHealth.Online
    val positiveIsConsumption = device.category.lowercase() != "solar"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (online) 1f else 0.6f),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = iconForCategory(device.category),
                contentDescription = device.category,
                size = 40.dp,
                cornerRadius = 12.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(device.name, fontSize = 14.sp)
                val subtitle = when (health) {
                    is DeviceHealth.Online -> stringResource(R.string.status_online) + (health.extraInfo?.let { " · $it" } ?: "")
                    is DeviceHealth.Offline -> health.lastError?.let { "${stringResource(R.string.status_offline)} · $it" } ?: stringResource(R.string.status_offline)
                }
                val subtitleColor = if (online) ems.idle else ems.consumption
                Text(subtitle, fontSize = 11.sp, color = subtitleColor)
            }
            if (health is DeviceHealth.Online) {
                Text(
                    text = formatWatts(health.powerW),
                    fontSize = 14.sp,
                    color = powerColor(powerSign(health.powerW, positiveIsConsumption)),
                )
            }
        }
    }
}
