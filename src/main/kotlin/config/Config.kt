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
data class Grid(val type: GridMeterType, val host: String)

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

@Serializable
enum class BatteryType {
    SMA_Sunny_Boy_Storage,
}

@Serializable
data class Battery(val type: BatteryType, val name: String, val host: String)
@Serializable
enum class CarType { BMW }

@Serializable
data class Car(
    val type: CarType,
    val name: String = "Car",
    val enabled: Boolean = false,
    val clientId: String = "",
    val vin: String = "",
    val brokerHost: String = "customer.streaming-cardata.bmwgroup.com",
    val brokerPort: Int = 9000,
)

@Serializable
data class Devices(
    val solar: List<Solar> = emptyList(),
    val heatPump: List<HeatPump> = emptyList(),
    val charger: List<Charger> = emptyList(),
    val battery: List<Battery> = emptyList(),
    val car: List<Car> = emptyList(),
)

@Serializable
data class OcppConfig(
    val enabled: Boolean,
    val heartbeatInterval: Int,
    val connectionTimeout: Int,
    val callTimeoutSeconds: Int = 30,
    val acceptUnknownChargePoints: Boolean = false,
    val acceptUnknownIdTags: Boolean = true,
    val autoProbeOnBoot: Boolean = true,
)

@Serializable
data class DatabaseConfig(val path: String = "ems.db")

@Serializable
data class WebSocketConfig(val username: String, val password: String)

/**
 * Where the effective device/settings config comes from.
 *
 * - [file]: the yaml file is the source of truth (today's behaviour); the runtime config API is
 *   read-only.
 * - [database]: the configurable subtree (devices + tuning) lives in SQLite and is editable at
 *   runtime via the config API. The DB is seeded from the yaml file on first use. Bootstrap fields
 *   ([database], [Config.clickhouse], [Config.websocket], [Config.refreshThreads], [configSource])
 *   are always taken from the file regardless of this setting.
 *
 * Lowercase names match the yaml (`configSource: file`).
 */
@Serializable
enum class ConfigSource { file, database }

/** Selectable control strategy (see ems/Strategy.kt). Editable tuning, applied at boot. */
@Serializable
enum class StrategyType { SurplusPriority, SimpleGridCompensation }

@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val database: DatabaseConfig = DatabaseConfig(),
    val clickhouse: ClickHouseConfig = ClickHouseConfig(),
    val refreshThreads : Int = 50,
    val configSource: ConfigSource = ConfigSource.file,
    val strategy: StrategyType = StrategyType.SurplusPriority,
    val pollIntervalMs: Long = 5_000,
)

/** Bootstrap/security fields are never taken from the DB document — always from the yaml file. */
fun Config.withBootstrapFrom(file: Config): Config = copy(
    database = file.database,
    clickhouse = file.clickhouse,
    websocket = file.websocket,
    refreshThreads = file.refreshThreads,
    configSource = file.configSource,
)

/** The credential that ships in the repo's dev `config.yaml`. Never acceptable on a real server. */
private const val DEV_WS_PASSWORD = "password"

fun Config.startupWarnings(): List<String> = buildList {
    if (websocket.password == DEV_WS_PASSWORD)
        add("Default WebSocket password in use — set websocket.password in config.yaml")
}

/**
 * Misconfigurations severe enough to abort the boot rather than warn.
 *
 * `/ws` authenticates by comparing against [WebSocketConfig] verbatim (see Sockets.kt), and that
 * socket sets EMS mode and steers the battery and charger. [Config.websocket] also *defaults* to
 * `user`/`password`, so a `websocket:` block that is missing, misindented, or left at the template
 * values does not fail loudly — it silently opens control to anyone who guesses the dev credential.
 * A warning is too easy to miss in the logs, so refuse to start instead.
 *
 * Only applied to a mounted production config: the bundled classpath resource legitimately ships the
 * dev credential for local runs and tests, so callers gate this on [externalConfigFile] being
 * non-null. See Application.kt.
 */
fun Config.fatalConfigErrors(): List<String> = buildList {
    if (websocket.password == DEV_WS_PASSWORD)
        add(
            "websocket.password is still the dev default — set a strong value in config.yaml. " +
                "This socket controls the battery and charger."
        )
    else if (websocket.password.isBlank())
        add("websocket.password is blank — set a strong value in config.yaml.")
}

/**
 * The external config file [loadConfig] would read, or null when it falls back to the bundled
 * classpath resource. Lets callers tell a mounted production config from the dev resource.
 */
fun externalConfigFile(
    filePath: String? = System.getenv("EMS_CONFIG") ?: "/config/config.yaml",
): File? = filePath?.let { File(it) }?.takeIf { it.exists() }

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
    val file = externalConfigFile(filePath)
    val builder = ConfigLoaderBuilder.default()
    val source = if (file != null) {
        builder.addFileSource(file)
    } else {
        builder.addResourceSource(resource)
    }
    return source.build().loadConfigOrThrow<Config>()
}
