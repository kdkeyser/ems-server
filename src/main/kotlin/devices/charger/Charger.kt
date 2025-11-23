package io.konektis.devices.charger

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

data class ChargerState(
    val currentPower: Watt
)

interface Charger {
    suspend fun update()
    val state: DeviceUpdate<ChargerState>?
}