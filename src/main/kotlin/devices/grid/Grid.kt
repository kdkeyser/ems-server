package io.konektis.devices.grid

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt

data class GridState(
    val power: Watt,
    val voltage: Volt
)

interface Grid {
    suspend fun update()
    suspend fun getState(): DeviceUpdate<GridState>?
}