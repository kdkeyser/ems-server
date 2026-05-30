package io.konektis.ems.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.TopologyView
import io.konektis.ems.ui.components.colorGray
import io.konektis.ems.ui.components.colorRed
import io.konektis.ems.ui.components.fmtWatts
import io.konektis.ems.ui.components.powerColor

@Composable
fun OverviewScreen(state: StatusState?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopologyView(state = state)

        val devices = state?.devices.orEmpty()
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Waiting for data…", fontSize = 14.sp, color = colorGray)
            }
        } else {
            devices.forEach { DeviceCard(it) }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceStatus) {
    val health = device.health
    val isOnline = health is DeviceHealth.Online

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "●",
                    fontSize = 12.sp,
                    color = if (isOnline) Color(0xFF4ADE80) else colorRed
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    when (health) {
                        is DeviceHealth.Offline -> Text(
                            health.lastError ?: "Offline",
                            fontSize = 11.sp,
                            color = colorRed
                        )
                        is DeviceHealth.Online  -> health.extraInfo?.let {
                            Text(it, fontSize = 11.sp, color = colorGray)
                        }
                    }
                }
            }

            if (health is DeviceHealth.Online) {
                Text(
                    text = fmtWatts(health.powerW),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = powerColor(health.powerW, device.category != "solar")
                )
            }
        }
    }
}
