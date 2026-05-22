package io.konektis.ems.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.ui.components.TopologyView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    connectionState: ConnectionState,
    onSettingsClick: () -> Unit
) {
    val status by vm.statusState.collectAsState()
    val controlVisible by vm.chargerControlVisible.collectAsState()
    var selectedMode by remember { mutableIntStateOf(0) }
    var maxPowerText by remember { mutableStateOf("7400") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("EMS") },
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )

        val (pillColor, pillText) = when (connectionState) {
            is ConnectionState.Connected    -> Color(0xFF34D399) to "Connected"
            is ConnectionState.Connecting   -> Color(0xFFFBBF24) to "Connecting…"
            is ConnectionState.Disconnected ->
                Color(0xFFF87171) to "Disconnected${connectionState.error?.let { " — $it" } ?: ""}"
        }
        Surface(
            color = pillColor.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(pillText, color = pillColor, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }

        TopologyView(state = status, modifier = Modifier.padding(16.dp))

        if (controlVisible) {
            ChargerControl(
                selectedMode = selectedMode,
                maxPowerText = maxPowerText,
                onModeChange = { idx ->
                    selectedMode = idx
                    val state = when (idx) {
                        0 -> ChargingState.NotCharging()
                        1 -> ChargingState.ChargingWithExcessPower()
                        2 -> ChargingState.ChargingWithMaxPower(maxPowerText.toUIntOrNull() ?: 7400u)
                        else -> ChargingState.NotCharging()
                    }
                    vm.setCharging(state)
                },
                onMaxPowerChange = { maxPowerText = it }
            )
        } else if (vm.controlState.collectAsState().value is ControlState.Unauthenticated) {
            Text(
                "Charger control unavailable — check credentials in Settings.",
                fontSize = 12.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargerControl(
    selectedMode: Int,
    maxPowerText: String,
    onModeChange: (Int) -> Unit,
    onMaxPowerChange: (String) -> Unit
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Charger", fontSize = 12.sp, color = Color(0xFF9CA3AF))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Off", "Solar surplus", "Max power").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = selectedMode == i,
                    onClick = { onModeChange(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)
                ) { Text(label, fontSize = 12.sp) }
            }
        }
        if (selectedMode == 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxPowerText,
                    onValueChange = onMaxPowerChange,
                    label = { Text("Max watts") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
