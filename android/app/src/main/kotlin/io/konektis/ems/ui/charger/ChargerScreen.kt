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
import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ChargerMode
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    chargerControl: ChargerControl?,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
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
                val online = uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = when {
                        isCharging && chargerW != null -> formatWatts(chargerW)
                        isCharging -> "Charging"
                        else -> "Idle"
                    },
                    valueColor = if (isCharging) ems.consumption else ems.idle,
                    statusText = when (uiState) {
                        ChargerUiState.CHARGING -> "Charging"
                        ChargerUiState.CONNECTED_IDLE -> "Connected — not charging"
                        else -> if (chargerW != null) "Charger online" else "Status unavailable"
                    },
                    online = online,
                )

                if (isAuthenticated && chargerControl != null) {
                    ChargerControls(
                        isCharging = isCharging,
                        control = chargerControl,
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
    control: ChargerControl,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
) {
    val ems = LocalEmsColors.current
    // EMS MANUAL makes solar surplus meaningless -> force Fixed.
    val solarAllowed = mode != ManagerMode.MANUAL
    var selectedMode by remember(control, mode) {
        mutableStateOf(if (control.mode == ChargerMode.SOLAR && solarAllowed) ChargerMode.SOLAR else ChargerMode.FIXED)
    }
    var fixedAmps by remember(control) { mutableIntStateOf(control.fixedAmps.coerceIn(6, 32)) }

    Text("MODE", style = MaterialTheme.typography.labelSmall, color = ems.idle)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedMode == ChargerMode.SOLAR,
            enabled = solarAllowed,
            onClick = { selectedMode = ChargerMode.SOLAR },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Solar surplus") }
        SegmentedButton(
            selected = selectedMode == ChargerMode.FIXED,
            onClick = { selectedMode = ChargerMode.FIXED },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Fixed power") }
    }

    if (!solarAllowed) {
        Text(
            "EMS is in manual mode — only fixed power applies to the charger.",
            fontSize = 12.sp,
            color = ems.idle,
        )
    }

    if (selectedMode == ChargerMode.FIXED) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Fixed current", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    Text("$fixedAmps A", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fixedAmps.toFloat(),
                    onValueChange = { fixedAmps = it.toInt() },
                    valueRange = 6f..32f,
                    steps = 25,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("6 A", fontSize = 11.sp, color = ems.idle)
                    Text("32 A", fontSize = 11.sp, color = ems.idle)
                }
            }
        }
    }

    Button(
        onClick = {
            onSetCharging(
                ChargerControl(mode = selectedMode, fixedAmps = fixedAmps, charging = !isCharging)
            )
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
