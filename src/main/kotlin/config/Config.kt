package io.konektis.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import kotlinx.serialization.Serializable
import java.io.File

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
    OCPP,
}

@Serializable
data class Charger(
    val type: ChargerType,
    val name: String,
    val host: String? = null,
    val chargingCurrent: ChargingCurrent,
    val chargePointId: String? = null,
    val connectorId: Int = 1,
)

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
data class OcppConfig(
    val enabled: Boolean,
    val heartbeatInterval: Int,
    val connectionTimeout: Int,
    val callTimeoutSeconds: Int = 30,
    val acceptUnknownChargePoints: Boolean = true,
    val acceptUnknownIdTags: Boolean = true,
    val autoProbeOnBoot: Boolean = true,
)

@Serializable
data class DatabaseConfig(val path: String = "ems.db")

@Serializable
data class WebSocketConfig(val username: String, val password: String)

@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val database: DatabaseConfig = DatabaseConfig(),
    val refreshThreads : Int = 50
)

/**
 * Loads config from an external file when one exists, otherwise from the bundled classpath resource.
 *
 * In a container the file lives at a mounted path (default `/config/config.yaml`, overridable with
 * the `EMS_CONFIG` env var) so device IPs/credentials/DB path can change without rebuilding the image.
 * Local runs and tests have no such file, so they fall back to the classpath `resource`.
 *
 * @param resource classpath resource to fall back to (e.g. "/config.yaml")
 * @param filePath external file to prefer; defaults to $EMS_CONFIG or "/config/config.yaml".
 *        Pass `null` to skip the file entirely (used by tests to force the resource path).
 */
fun loadConfig(
    resource: String,
    filePath: String? = System.getenv("EMS_CONFIG") ?: "/config/config.yaml",
): Config {
    val file = filePath?.let { File(it) }
    val builder = ConfigLoaderBuilder.default()
    val source = if (file != null && file.exists()) {
        builder.addFileSource(file)
    } else {
        builder.addResourceSource(resource)
    }
    return source.build().loadConfigOrThrow<Config>()
}
