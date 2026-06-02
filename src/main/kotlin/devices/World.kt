package io.konektis.devices

import io.konektis.config.BatteryType
import io.konektis.config.ChargerType
import io.konektis.config.Config
import io.konektis.config.GridMeterType
import io.konektis.config.HeatPumpType
import io.konektis.config.SolarType
import io.konektis.devices.Heatpump.DaikinHeatpump
import io.konektis.devices.Solar.SMASolar
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.SMABattery
import io.konektis.devices.charger.Charger
import io.konektis.devices.charger.OcppCharger
import io.konektis.devices.charger.Webasto
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridProperties
import io.konektis.devices.grid.P1Meter
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.ocpp.OcppService

data class World(
    val grid: Grid,
    val chargers : Map<String, Charger>,
    val solar : Map<String, Solar>,
    val smartConsumers : Map<String, SmartConsumer>,
    val batteries : Map<String, Battery>
    )
{
    companion object {
        fun fromConfig(config: Config, ocppService: OcppService): World {
            val grid = when (config.grid.type) {
                GridMeterType.P1HomeWizard -> P1Meter(config.grid.host, GridProperties(config.grid.gridType))
            }
            val chargers = config.devices.charger.associate {
                when (it.type) {
                    ChargerType.WebastoUnite -> Pair(it.name, Webasto(it.host!!))
                    ChargerType.OCPP -> Pair(it.name, OcppCharger(it.chargePointId!!, it.connectorId, ocppService))
                }
            }
            val solar = config.devices.solar.associate {
                when (it.type) {
                    SolarType.SMA_Sunny_Boy -> Pair(it.name, SMASolar(it.host))
                }
            }
            val smartConsumers = config.devices.heatPump.associate {
                when (it.type) {
                    HeatPumpType.DaikinHomeHub -> Pair(it.name, DaikinHeatpump(it.host))
                }
            }
            val batteries = config.devices.battery.associate {
                when (it.type) {
                    BatteryType.SMA_Sunny_Boy_Storage -> Pair(it.name, SMABattery(it.host))
                }
            }
            return World(
                grid,
                chargers,
                solar,
                smartConsumers,
                batteries
            )
        }
    }
}
