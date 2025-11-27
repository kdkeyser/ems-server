package io.konektis.devices.grid

import io.konektis.config.GridType
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt



data class GridState(
    val power: Watt,
    val voltage: Volt
)

data class GridProperties(
    val gridType : GridType
)

interface Grid {
    suspend fun update()
    val properties : GridProperties
    suspend fun getState(): DeviceUpdate<GridState>?
}