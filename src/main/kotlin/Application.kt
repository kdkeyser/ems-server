package io.konektis

import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.konektis.config.ClickHouseConfig
import io.konektis.config.loadConfig
import io.konektis.config.startupWarnings
import io.konektis.config.WebSocketConfig
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.Database

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

        val config = loadConfig("/config.yaml")

        logger.info("EMS server starting")
        logger.info("Grid: ${config.grid.type} @ ${config.grid.host} (${config.grid.gridType})")
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
        val component = AppComponent::class.create(config)

        val dataCollector = component.dataCollector
        val energyManager = component.energyManager

        // Graceful hand-back: on normal stop/restart, release every battery to the inverter (803).
        // Bounded so a hung Modbus write can't wedge shutdown. Hard kill / power loss is out of
        // scope — no software can write 803 when the process is gone (the SMA watchdog is too slow).
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                withTimeoutOrNull(3_000) {
                    component.world.batteries.values.forEach {
                        runCatching { it.releaseToInverter() }
                    }
                }
            }
        })

        coroutineScope {
            launch {
                while (true) {
                    dataCollector.refresh()
                    delay(5000)
                }
            }
            energyManager.loadChargerControl()
            launch { energyManager.run() }
            launch { component.carDataService.start() }
            launch { component.historyWriter.run(energyManager.emsHistoryFlow) }
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(
                        energyManager, config.websocket, dataCollector.statusStateFlow,
                        component.ocppService, component.database,
                        component.historyRepository, config.clickhouse,
                    )
                }
                server.start(wait = true)
            }
        }

    }
}

fun Application.module(
    energyManager: EnergyManager, wsConfig: WebSocketConfig, statusFlow: Flow<StatusState?>,
    ocppService: io.konektis.ocpp.OcppService, database: Database,
    historyRepository: HistoryRepository, clickhouse: ClickHouseConfig,
) {
    install(ContentNegotiation) { json() }
    configureSecurity(wsConfig)
    configureAdministration()
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer(ocppService)
    configureOcppWebUi(ocppService, energyManager)
    configureDatabases(database)
    configureHistoryAuthenticated(clickhouse, historyRepository)
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
