package io.konektis.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(val port: Int, val appCredentials: Map<String, String>)

@Serializable
data class EnergyManagerConfig(val config: Map<String, String>)

@Serializable
data class ChargingCurrent(val min: Double, val max: Double)

@Serializable
data class Solar(val type: String, val name: String, val host: String)

@Serializable
data class Grid(val type: String, val gridType: String, val host: String)

@Serializable
data class HeatPump(val type: String, val host: String)

@Serializable
data class Charger(val type: String, val host: String, val chargingCurrent: ChargingCurrent)

@Serializable
data class Devices(
    val solar: List<Solar>? = null,
    val grid: List<Grid>? = null,
    val heatPump: List<HeatPump>? = null,
    val charger: List<Charger>? = null
)

@Serializable
data class OcppConfig(val enabled: Boolean, val heartbeatInterval: Int, val connectionTimeout: Int)

@Serializable
data class Config(
    val server: ServerConfig,
    val energyManager: EnergyManagerConfig,
    val devices: Devices,
    val ocpp: OcppConfig
)

fun loadConfig(resource: String): Config {
    return ConfigLoaderBuilder.default()
        .addResourceSource(resource)
        .build()
        .loadConfigOrThrow<Config>()
}
