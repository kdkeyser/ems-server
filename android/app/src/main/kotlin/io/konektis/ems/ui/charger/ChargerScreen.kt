package io.konektis.ems.ui.charger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

private enum class ChargingMode { SOLAR, MANUAL }

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    onSetCharging: (ChargingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    val chargerW = statusState?.chargerW
    val isCharging = chargerW != null && chargerW > 0
    val isAuthenticated = controlState is ControlState.Authenticated
    var chargingMode by remember { mutableStateOf(ChargingMode.SOLAR) }
    var manualPower by remember { mutableIntStateOf(3680) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusHero(
            icon = EmsIcons.Charger,
            value = when {
                chargerW == null -> "—"
                isCharging -> formatWatts(chargerW)
                else -> "Idle"
            },
            valueColor = if (isCharging) ems.consumption else ems.idle,
            statusText = if (isCharging) "Charging · Webasto" else "Not charging",
            online = chargerW != null,
        )

        if (isAuthenticated) {
            Text("MODE", style = MaterialTheme.typography.labelSmall, color = ems.idle)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = chargingMode == ChargingMode.SOLAR,
                    onClick = { chargingMode = ChargingMode.SOLAR },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Solar surplus") }
                SegmentedButton(
                    selected = chargingMode == ChargingMode.MANUAL,
                    onClick = { chargingMode = ChargingMode.MANUAL },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Fixed power") }
            }

            if (chargingMode == ChargingMode.MANUAL) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Max power", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                            Text(formatWatts(manualPower), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = manualPower.toFloat(),
                            onValueChange = { manualPower = it.toInt() },
                            valueRange = 1440f..7680f,
                            steps = 25,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("1.4 kW", fontSize = 11.sp, color = ems.idle)
                            Text("7.7 kW", fontSize = 11.sp, color = ems.idle)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (isCharging) {
                        onSetCharging(ChargingState.NotCharging)
                    } else {
                        onSetCharging(
                            when (chargingMode) {
                                ChargingMode.SOLAR -> ChargingState.ChargingWithExcessPower
                                ChargingMode.MANUAL -> ChargingState.ChargingWithMaxPower(manualPower.toUInt())
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCharging) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    if (isCharging) "STOP CHARGING" else "START CHARGING",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                "Charger control unavailable — check credentials in Settings.",
                fontSize = 14.sp,
                color = ems.idle,
            )
        }
    }
}
