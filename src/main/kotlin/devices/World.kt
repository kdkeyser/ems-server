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
import io.konektis.devices.grid.P1Meter
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.ocpp.OcppService

data class World(
    val grid: Grid,
    val charger: Charger?,
    val solar: Map<String, Solar>,
    val heatPump: SmartConsumer?,
    val battery: Battery?,
) {
    companion object {
        fun fromConfig(config: Config, ocppService: OcppService): World {
            require(config.devices.charger.size <= 1) {
                "Only one charger is supported; got ${config.devices.charger.map { it.name }}"
            }
            require(config.devices.battery.size <= 1) {
                "Only one battery is supported; got ${config.devices.battery.map { it.name }}"
            }
            require(config.devices.heatPump.size <= 1) {
                "Only one heat pump is supported; got ${config.devices.heatPump.map { it.name }}"
            }
            val grid = when (config.grid.type) {
                GridMeterType.P1HomeWizard -> P1Meter(config.grid.host)
            }
            val charger = config.devices.charger.firstOrNull()?.let {
                when (it.type) {
                    ChargerType.WebastoUnite -> Webasto(it.host!!)
                    ChargerType.OCPP -> OcppCharger(it.chargePointId!!, it.connectorId, ocppService)
                }
            }
            val solar = config.devices.solar.associate {
                when (it.type) {
                    SolarType.SMA_Sunny_Boy -> Pair(it.name, SMASolar(it.host))
                }
            }
            val heatPump = config.devices.heatPump.firstOrNull()?.let {
                when (it.type) {
                    HeatPumpType.DaikinHomeHub -> DaikinHeatpump(it.host)
                }
            }
            val battery = config.devices.battery.firstOrNull()?.let {
                when (it.type) {
                    BatteryType.SMA_Sunny_Boy_Storage -> SMABattery(it.host)
                }
            }
            return World(grid, charger, solar, heatPump, battery)
        }
    }
}
