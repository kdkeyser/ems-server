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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

/** What the charger scene draws for a given UI state. */
data class ChargerSceneSpec(
    val showCar: Boolean,
    val carDimmed: Boolean,
    val charging: Boolean,
)

fun chargerSceneSpec(uiState: ChargerUiState): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR -> ChargerSceneSpec(showCar = true, carDimmed = true, charging = false)
    ChargerUiState.CONNECTED_IDLE -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = false)
    ChargerUiState.CHARGING -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = true)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, charging = false)
}

private val ChargingAmber = Color(0xFFFBBF24)
private val CableGrey = Color(0xFF334155)

/** Charger → (cable + bolt) → car. Bolt pulses while charging; car dims to a ghost when absent. */
@Composable
fun ChargerScene(spec: ChargerSceneSpec, modifier: Modifier = Modifier) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(EmsIcons.Charger, contentDescription = "Charger", tint = iconTint, modifier = Modifier.size(46.dp))
        if (spec.showCar) {
            Cable(live = spec.charging)
            if (spec.charging) {
                PulsingBolt()
                Cable(live = true)
            }
            Icon(
                EmsIcons.Car,
                contentDescription = "Car",
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
private fun PulsingBolt() {
    val t = rememberInfiniteTransition(label = "bolt")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha",
    )
    val s by t.animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "scale",
    )
    Icon(
        EmsIcons.Charging,
        contentDescription = "Charging",
        tint = ChargingAmber,
        modifier = Modifier.size(24.dp).graphicsLayer { alpha = a; scaleX = s; scaleY = s },
    )
}

/** Charger-tab hero: the scene, the big value, and the status line with a colored dot. */
@Composable
fun ChargerHero(uiState: ChargerUiState, chargerW: Int?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val isCharging = uiState == ChargerUiState.CHARGING
    val online = uiState != ChargerUiState.NO_CAR &&
        (uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null)
    val value = when {
        uiState == ChargerUiState.NO_CAR -> "No car"
        isCharging && chargerW != null -> formatWatts(chargerW)
        isCharging -> "Charging"
        uiState == ChargerUiState.CONTROLS_FALLBACK && chargerW != null -> formatWatts(chargerW)
        else -> "Idle"
    }
    val statusText = when (uiState) {
        ChargerUiState.NO_CAR -> "No car connected"
        ChargerUiState.CHARGING -> "Charging"
        ChargerUiState.CONNECTED_IDLE -> "Connected — not charging"
        ChargerUiState.CONTROLS_FALLBACK -> if (chargerW != null) "Charger online" else "Status unavailable"
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChargerScene(chargerSceneSpec(uiState))
            Text(value, color = if (isCharging) ems.consumption else ems.idle, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
