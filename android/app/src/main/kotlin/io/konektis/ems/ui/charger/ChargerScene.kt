package io.konektis.ems.ui.charger

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.R
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

/** Bolt rendering: OFF = none, ARMED = dim static (session on, no power), CHARGING = pulsing (power flowing). */
enum class BoltMode { OFF, ARMED, CHARGING }

/** What the charger scene draws for a given UI + session state. */
data class ChargerSceneSpec(
    val showCar: Boolean,
    val carDimmed: Boolean,
    val bolt: BoltMode,
)

fun chargerSceneSpec(uiState: ChargerUiState, sessionActive: Boolean): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR -> ChargerSceneSpec(showCar = true, carDimmed = true, bolt = BoltMode.OFF)
    ChargerUiState.CONNECTED_IDLE -> ChargerSceneSpec(showCar = true, carDimmed = false, bolt = if (sessionActive) BoltMode.ARMED else BoltMode.OFF)
    ChargerUiState.CHARGING -> ChargerSceneSpec(showCar = true, carDimmed = false, bolt = BoltMode.CHARGING)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, bolt = BoltMode.OFF)
}

/** Start/Stop button appearance, derived from the session intent and any in-flight press. */
enum class ChargerButtonLabel { START, STOP, STARTING, STOPPING }
data class ChargerButtonState(val label: ChargerButtonLabel, val enabled: Boolean, val stopStyle: Boolean)

/** pending: true = starting, false = stopping, null = settled. */
fun chargerButtonState(sessionActive: Boolean, pending: Boolean?): ChargerButtonState = when (pending) {
    true -> ChargerButtonState(ChargerButtonLabel.STARTING, enabled = false, stopStyle = false)
    false -> ChargerButtonState(ChargerButtonLabel.STOPPING, enabled = false, stopStyle = true)
    null -> if (sessionActive) ChargerButtonState(ChargerButtonLabel.STOP, enabled = true, stopStyle = true)
            else ChargerButtonState(ChargerButtonLabel.START, enabled = true, stopStyle = false)
}

private val ChargingAmber = Color(0xFFFBBF24)
private val CableGrey = Color(0xFF334155)

/** Charger → (cable + bolt) → car. Pulsing bolt = power flowing; dim static bolt = session armed. */
@Composable
fun ChargerScene(spec: ChargerSceneSpec, modifier: Modifier = Modifier) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(EmsIcons.Charger, contentDescription = stringResource(R.string.cd_charger), tint = iconTint, modifier = Modifier.size(46.dp))
        if (spec.showCar) {
            Cable(live = spec.bolt == BoltMode.CHARGING)
            if (spec.bolt != BoltMode.OFF) {
                Bolt(spec.bolt)
                Cable(live = spec.bolt == BoltMode.CHARGING)
            }
            Icon(
                EmsIcons.Car,
                contentDescription = stringResource(R.string.cd_car),
                tint = iconTint,
                modifier = Modifier.size(46.dp).alpha(if (spec.carDimmed) 0.16f else 1f),
            )
        }
    }
}

@Composable
private fun Cable(live: Boolean) {
    Box(
        Modifier
            .width(16.dp)
            .height(3.dp)
            .background(if (live) ChargingAmber else CableGrey, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun Bolt(mode: BoltMode) {
    if (mode == BoltMode.CHARGING) {
        val t = rememberInfiniteTransition(label = "bolt")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
        val s by t.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "scale")
        Icon(
            EmsIcons.Charging,
            contentDescription = stringResource(R.string.cd_charging),
            tint = ChargingAmber,
            modifier = Modifier.size(24.dp).graphicsLayer { alpha = a; scaleX = s; scaleY = s },
        )
    } else { // ARMED — session on, no power flowing
        Icon(
            EmsIcons.Charging,
            contentDescription = stringResource(R.string.cd_charging),
            tint = ChargingAmber,
            modifier = Modifier.size(24.dp).alpha(0.4f),
        )
    }
}

/** Charger-tab hero: the scene, the big value, and the status line with a colored dot. */
@Composable
fun ChargerHero(uiState: ChargerUiState, chargerW: Int?, sessionActive: Boolean, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val isCharging = uiState == ChargerUiState.CHARGING
    val online = uiState != ChargerUiState.NO_CAR &&
        (uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null)
    val value = when {
        uiState == ChargerUiState.NO_CAR -> stringResource(R.string.charger_value_no_car)
        isCharging && chargerW != null -> formatWatts(chargerW)
        isCharging -> stringResource(R.string.charger_charging)
        uiState == ChargerUiState.CONNECTED_IDLE && sessionActive -> stringResource(R.string.charger_value_ready)
        uiState == ChargerUiState.CONTROLS_FALLBACK && chargerW != null -> formatWatts(chargerW)
        else -> stringResource(R.string.charger_value_idle)
    }
    val statusText = when {
        uiState == ChargerUiState.NO_CAR -> stringResource(R.string.charger_status_no_car)
        isCharging -> stringResource(R.string.charger_charging)
        uiState == ChargerUiState.CONNECTED_IDLE && sessionActive -> stringResource(R.string.charger_status_session_not_drawing)
        uiState == ChargerUiState.CONTROLS_FALLBACK -> if (chargerW != null) stringResource(R.string.charger_status_online) else stringResource(R.string.charger_status_unavailable)
        else -> stringResource(R.string.charger_status_connected_idle)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChargerScene(chargerSceneSpec(uiState, sessionActive))
            Text(value, color = if (isCharging) ems.consumption else ems.idle, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
