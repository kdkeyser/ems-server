package io.konektis.devices

import io.konektis.devices.battery.Battery
import io.konektis.devices.charger.Charger
import io.konektis.devices.grid.Grid
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar

data class World(
    val grid: Grid,
    val chargers : Map<String, Charger>,
    val solar : Map<String, Solar>,
    val smartConsumers : Map<String, SmartConsumer>,
    val batteries : Map<String, Battery>
    )