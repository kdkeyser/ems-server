package io.konektis.ems.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector

/** Central mapping of energy nodes/devices to Material vector icons (no emoji). */
object EmsIcons {
    val Solar: ImageVector = Icons.Filled.SolarPower
    val Grid: ImageVector = Icons.Filled.Bolt
    val House: ImageVector = Icons.Filled.Home
    val Battery: ImageVector = Icons.Filled.BatteryChargingFull
    val Charger: ImageVector = Icons.Filled.EvStation
    val HeatPump: ImageVector = Icons.Filled.Thermostat
}

/** Maps a device's `category` string (from StatusState) to an icon; falls back to Bolt. */
fun iconForCategory(category: String): ImageVector = when (category.lowercase()) {
    "solar" -> EmsIcons.Solar
    "grid" -> EmsIcons.Grid
    "battery" -> EmsIcons.Battery
    "charger", "car_charger" -> EmsIcons.Charger
    "heatpump", "heat_pump" -> EmsIcons.HeatPump
    else -> EmsIcons.Grid
}
