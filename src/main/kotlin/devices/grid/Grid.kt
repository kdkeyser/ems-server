package io.konektis.devices.grid

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt

enum class GridType {
    Ph1,
    Ph3_400V,
    Ph3_230V
}

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
    val state: DeviceUpdate<GridState>?
}