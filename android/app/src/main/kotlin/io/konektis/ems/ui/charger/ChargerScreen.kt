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
import io.konektis.ems.data.model.ChargerConnection
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

private enum class ChargingMode { SOLAR, MANUAL }

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    chargingState: ChargingState?,
    mode: ManagerMode?,
    onSetCharging: (ChargingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    val chargerW = statusState?.chargerW
    val connection = parseChargerConnection(statusState?.chargerConnection)
    val uiState = chargerUiState(connection)
    val isAuthenticated = controlState is ControlState.Authenticated
    val isCharging = uiState == ChargerUiState.CHARGING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (uiState) {
            ChargerUiState.NO_CAR -> {
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = "No car",
                    valueColor = ems.idle,
                    statusText = "No car connected",
                    online = false,
                )
            }
            else -> {
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = when {
                        isCharging && chargerW != null -> formatWatts(chargerW)
                        isCharging -> "Charging"
                        else -> "Idle"
                    },
                    valueColor = if (isCharging) ems.consumption else ems.idle,
                    statusText = if (isCharging) "Charging" else "Connected — not charging",
                    online = true,
                )

                if (isAuthenticated) {
                    ChargerControls(
                        isCharging = isCharging,
                        chargingState = chargingState,
                        mode = mode,
                        onSetCharging = onSetCharging,
                    )
                } else {
                    Text(
                        "Charger control unavailable — check credentials in Settings.",
                        fontSize = 14.sp,
                        color = ems.idle,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargerControls(
    isCharging: Boolean,
    chargingState: ChargingState?,
    mode: ManagerMode?,
    onSetCharging: (ChargingState) -> Unit,
) {
    val ems = LocalEmsColors.current
    // Initialise from the server's authoritative intent; re-keyed when the server pushes a change.
    var chargingMode by remember(chargingState) {
        mutableStateOf(
            if (chargingState is ChargingState.ChargingWithMaxPower) ChargingMode.MANUAL
            else ChargingMode.SOLAR
        )
    }
    var manualPower by remember(chargingState) {
        mutableIntStateOf((chargingState as? ChargingState.ChargingWithMaxPower)?.maxPower?.toInt() ?: 3680)
    }

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

    if (mode == ManagerMode.MANUAL && chargingMode == ChargingMode.SOLAR) {
        Text(
            "EMS is in manual mode — solar-surplus charging is best-effort and may compete with the battery.",
            fontSize = 12.sp,
            color = ems.idle,
        )
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
}
