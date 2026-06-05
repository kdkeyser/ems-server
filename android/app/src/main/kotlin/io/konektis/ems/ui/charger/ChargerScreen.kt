package io.konektis.ems.ui.charger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.R
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ChargerMode
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.theme.LocalEmsColors
import kotlinx.coroutines.delay

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ChargerHero(
            uiState = uiState,
            chargerW = chargerW,
            sessionActive = chargerControl?.charging ?: false,
        )

        if (uiState != ChargerUiState.NO_CAR) {
            if (isAuthenticated && chargerControl != null) {
                ChargerControls(
                    control = chargerControl,
                    mode = mode,
                    onSetCharging = onSetCharging,
                )
            } else {
                Text(
                    stringResource(R.string.charger_control_unavailable),
                    fontSize = 14.sp,
                    color = ems.idle,
                )
            }
        }
    }
}

@Composable
private fun ChargerControls(
    control: ChargerControl,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
) {
    val ems = LocalEmsColors.current
    val sessionActive = control.charging
    // EMS MANUAL makes solar surplus meaningless -> force Fixed.
    val solarAllowed = mode != ManagerMode.MANUAL
    var selectedMode by remember(control, mode) {
        mutableStateOf(if (control.mode == ChargerMode.SOLAR && solarAllowed) ChargerMode.SOLAR else ChargerMode.FIXED)
    }
    var fixedAmps by remember(control) { mutableIntStateOf(control.fixedAmps.coerceIn(6, 32)) }

    // pending: true = starting, false = stopping, null = settled.
    var pending by remember { mutableStateOf<Boolean?>(null) }
    // Clear pending once the server-echoed session reaches the pressed target.
    LaunchedEffect(sessionActive, pending) {
        if (pending != null && pending == sessionActive) pending = null
    }
    // Safety timeout so the spinner can't hang if the session never flips.
    LaunchedEffect(pending) {
        if (pending != null) { delay(30_000); pending = null }
    }

    Text(stringResource(R.string.charger_mode_label), style = MaterialTheme.typography.labelSmall, color = ems.idle)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedMode == ChargerMode.SOLAR,
            enabled = solarAllowed,
            onClick = { selectedMode = ChargerMode.SOLAR },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.charger_mode_solar)) }
        SegmentedButton(
            selected = selectedMode == ChargerMode.FIXED,
            onClick = { selectedMode = ChargerMode.FIXED },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.charger_mode_fixed)) }
    }

    if (!solarAllowed) {
        Text(stringResource(R.string.charger_manual_note), fontSize = 12.sp, color = ems.idle)
    }

    if (selectedMode == ChargerMode.FIXED) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.charger_fixed_current), style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    Text(stringResource(R.string.charger_amps, fixedAmps), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                    Text(stringResource(R.string.charger_amps, 6), fontSize = 11.sp, color = ems.idle)
                    Text(stringResource(R.string.charger_amps, 32), fontSize = 11.sp, color = ems.idle)
                }
            }
        }
    }

    val btn = chargerButtonState(sessionActive, pending)
    val label = when (btn.label) {
        ChargerButtonLabel.START -> stringResource(R.string.charger_start)
        ChargerButtonLabel.STOP -> stringResource(R.string.charger_stop)
        ChargerButtonLabel.STARTING -> stringResource(R.string.charger_starting)
        ChargerButtonLabel.STOPPING -> stringResource(R.string.charger_stopping)
    }
    Button(
        onClick = {
            val target = !sessionActive
            pending = target
            onSetCharging(ChargerControl(mode = selectedMode, fixedAmps = fixedAmps, charging = target))
        },
        enabled = btn.enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (btn.stopStyle) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.primary,
        ),
    ) {
        if (!btn.enabled) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = if (btn.stopStyle) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
