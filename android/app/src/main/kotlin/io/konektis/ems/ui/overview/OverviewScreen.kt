package io.konektis.ems.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.DeviceCard
import io.konektis.ems.ui.components.EnergyFlowDiagram
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun OverviewScreen(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EnergyFlowDiagram(state = state)

        val devices = state?.devices.orEmpty()
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Waiting for data…", fontSize = 14.sp, color = ems.idle)
            }
        } else {
            Text(
                "DEVICES",
                style = MaterialTheme.typography.labelSmall,
                color = ems.idle,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
            devices.forEach { DeviceCard(it) }
        }
    }
}
