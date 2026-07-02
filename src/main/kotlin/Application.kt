package io.konektis

import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.konektis.config.ClickHouseConfig
import io.konektis.config.ConfigService
import io.konektis.config.ConfigSource
import io.konektis.config.ConfigStore
import io.konektis.config.configureConfigApi
import io.konektis.config.loadConfig
import io.konektis.config.startupWarnings
import io.konektis.config.WebSocketConfig
import io.konektis.ocpp.db.openDatabase
import io.konektis.history.HistoryRepository
import io.konektis.history.configureHistoryAuthenticated
import io.konektis.di.AppComponent
import io.konektis.di.create
import io.konektis.ems.EnergyManager
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.konektis.ocpp.configureOcppServer
import io.konektis.ocpp.configureOcppWebUi
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

suspend fun main(args: Array<String>) {
    Main().main(args)
}

class Main : Klogging {
    suspend fun main(args: Array<String>) {
        loggingConfiguration {
            sink("stdout", RENDER_SIMPLE, STDOUT)
            logging {
                fromLoggerBase("io.konektis")
                fromMinLevel(Level.TRACE) {
                    toSink("stdout")
                }
            }
        }

        val fileConfig = loadConfig("/config.yaml")
        // Resolve the effective config: in `file` mode this is the yaml as-is; in `database` mode it
        // comes from SQLite (seeded from the yaml on first use), with bootstrap fields kept from the
        // file. The DB path itself is bootstrap, so it always comes from the file.
        val configStore = ConfigStore(openDatabase(fileConfig.database.path)).also { it.init() }
        val configService = ConfigService(fileConfig, configStore)
        val config = configService.resolve()

        logger.info("EMS server starting")
        logger.info("Config source: ${config.configSource}")
        logger.info("Grid: ${config.grid.type} @ ${config.grid.host}")
        if (config.devices.solar.isNotEmpty())
            logger.info("Solar: ${config.devices.solar.joinToString { "${it.name} @ ${it.host}" }}")
        if (config.devices.charger.isNotEmpty())
            logger.info("Chargers: ${config.devices.charger.joinToString { "${it.name} @ ${it.host}" }}")
        if (config.devices.battery.isNotEmpty())
            logger.info("Batteries: ${config.devices.battery.joinToString { "${it.name} @ ${it.host}" }}")
        if (config.devices.heatPump.isNotEmpty())
            logger.info("Heat pumps: ${config.devices.heatPump.joinToString { "${it.name} @ ${it.host}" }}")
        logger.info("Refresh interval: 5s, threads: ${config.refreshThreads}")
        config.startupWarnings().forEach { logger.warn(it) }

        // Create the DI component
        val component = AppComponent::class.create(config, configService)

        val dataCollector = component.dataCollector
        val energyManager = component.energyManager

        // Graceful hand-back: on normal stop/restart, release every battery to the inverter (803).
        // Bounded so a hung Modbus write can't wedge shutdown. Hard kill / power loss is out of
        // scope — no software can write 803 when the process is gone (the SMA watchdog is too slow).
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                withTimeoutOrNull(3_000) {
                    component.worldHolder.current.battery?.let {
                        runCatching { it.releaseToInverter() }
                    }
                }
            }
        })

        val worldHolder = component.worldHolder
        // The poll/control tick and config-driven graph rebuilds must never overlap: a swap that lands
        // mid-tick could steer the wrong (half-torn-down) battery. One mutex serialises both.
        val reloadMutex = Mutex()

        coroutineScope {
            energyManager.loadChargerControl()
            launch {
                while (true) {
                    reloadMutex.withLock {
                        dataCollector.refresh()
                        energyManager.tick()
                    }
                    delay(configService.current().pollIntervalMs)
                }
            }
            // Hot-reload: rebuild the live device graph whenever the (validated) config changes. Only
            // fires in `database` mode; `file` mode never emits past the initial value. drop(1) skips
            // that initial value (already applied at boot).
            launch {
                configService.configFlow.drop(1).collect { newConfig ->
                    reloadMutex.withLock {
                        runCatching {
                            val newWorld = io.konektis.devices.World.fromConfig(newConfig, component.ocppService)
                            worldHolder.swap(newWorld).shutdown()
                        }.onFailure { logger.error("Config reload failed; keeping the previous device graph", it) }
                            .onSuccess { logger.info("Reloaded device graph from config revision ${configService.version}") }
                    }
                }
            }
            launch { component.carDataService.start() }
            launch { component.historyWriter.run(energyManager.emsHistoryFlow) }
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(
                        energyManager, config.websocket, dataCollector.statusStateFlow,
                        component.ocppService,
                        component.historyRepository, config.clickhouse,
                        configService,
                    )
                }
                server.start(wait = true)
            }
        }

    }
}

fun Application.module(
    energyManager: EnergyManager, wsConfig: WebSocketConfig, statusFlow: Flow<StatusState?>,
    ocppService: io.konektis.ocpp.OcppService,
    historyRepository: HistoryRepository, clickhouse: ClickHouseConfig,
    configService: io.konektis.config.ConfigService,
) {
    install(ContentNegotiation) { json() }
    configureSecurity(wsConfig)
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer(ocppService)
    configureOcppWebUi(ocppService, energyManager)
    configureHistoryAuthenticated(clickhouse, historyRepository)
    configureConfigApi(configService)
    configureMonitoring()
}
