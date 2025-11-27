package io.konektis.devices.solar

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt

data class SolarState(
    val power: Watt
)
interface Solar {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<SolarState>?
}