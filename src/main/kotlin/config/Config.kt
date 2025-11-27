package io.konektis.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import kotlinx.serialization.Serializable

@Serializable
data class ChargingCurrent(val min: Double, val max: Double)

@Serializable
enum class SolarType {
    SMA_Sunny_Boy,
}

@Serializable
data class Solar(val type: SolarType, val name: String, val host: String)

@Serializable
enum class GridMeterType {
    P1HomeWizard,
}

@Serializable
enum class GridType {
    Phase1,
    Phase3_230V,
    Phase3_400V
}
@Serializable
data class Grid(val type: GridMeterType, val gridType: GridType, val host: String)

@Serializable
enum class HeatPumpType {
    DaikinHomeHub,
}
@Serializable
data class HeatPump(val type: HeatPumpType, val name: String, val host: String)

@Serializable
enum class ChargerType {
    WebastoUnite,
}
@Serializable
data class Charger(val type: ChargerType, val name: String, val host: String, val chargingCurrent: ChargingCurrent)

enum class BatteryType {
    SMA_Sunny_Boy_Storage,
}

@Serializable
data class Battery(val type: BatteryType, val name: String, val host: String)
@Serializable
data class Devices(
    val solar: List<Solar> = emptyList(),
    val heatPump: List<HeatPump> = emptyList(),
    val charger: List<Charger> = emptyList(),
    val battery: List<Battery> = emptyList()
)

@Serializable
data class OcppConfig(val enabled: Boolean, val heartbeatInterval: Int, val connectionTimeout: Int)

@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val refreshThreads : Int = 50
)

fun loadConfig(resource: String): Config {
    return ConfigLoaderBuilder.default()
        .addResourceSource(resource)
        .build()
        .loadConfigOrThrow<Config>()
}
