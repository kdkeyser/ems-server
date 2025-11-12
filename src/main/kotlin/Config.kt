package io.konektis

data class ServerConfig(val port : Int, val appCredentials: Map<String, String>)
data class EnergyManagerConfig(val config: Map<String, String>)
data class Config(val server: ServerConfig, val energyManager: EnergyManagerConfig)