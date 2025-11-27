package io.konektis.devices.charger

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

data class ChargerState(
    val currentPower: Watt
)

interface Charger {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<ChargerState>?
    suspend fun setMaxChargerPower(power: Watt)
}