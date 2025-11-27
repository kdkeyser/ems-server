package io.konektis.devices.battery

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.devices.charger.ChargerState

data class BatteryState(
    val charge: UShort,
    val power: Watt
)
interface Battery {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<BatteryState>?
}

