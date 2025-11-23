package io.konektis.devices.battery

import io.konektis.devices.Watt

data class BatteryState(
    val charge: UShort,
    val power: Watt
)
interface Battery {
}

