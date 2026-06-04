package io.konektis.devices.charger

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

/** Whether a car is plugged in / charging, derived from OCPP connector status where available. */
enum class ChargerConnection { NotConnected, Connected, Charging, Unknown }

data class ChargerState(
    val currentPower: Watt,
    val connection: ChargerConnection = ChargerConnection.Unknown
)

interface Charger {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<ChargerState>?
    suspend fun setMaxChargerPower(power: Watt)
}