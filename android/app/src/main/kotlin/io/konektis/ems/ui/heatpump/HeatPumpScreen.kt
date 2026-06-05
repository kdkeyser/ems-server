package io.konektis.ems.ui.heatpump

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.R
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun HeatPumpScreen(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val device = state?.devices?.firstOrNull { it.category.lowercase() in setOf("heatpump", "heat_pump") }
    val health = device?.health
    val online = health is DeviceHealth.Online
    val powerW = (health as? DeviceHealth.Online)?.powerW ?: state?.heatpumpW

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHero(
            icon = EmsIcons.HeatPump,
            value = if (online) formatWatts(powerW) else "—",
            valueColor = if (online) ems.consumption else ems.idle,
            statusText = when (health) {
                is DeviceHealth.Online -> stringResource(R.string.status_online) + (health.extraInfo?.let { " · $it" } ?: "")
                is DeviceHealth.Offline -> health.lastError?.let { "${stringResource(R.string.status_offline)} · $it" } ?: stringResource(R.string.status_offline)
                null -> stringResource(R.string.status_no_data)
            },
            online = online,
        )
        Text(
            stringResource(R.string.heatpump_no_controls),
            fontSize = 12.sp,
            color = ems.idle,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
