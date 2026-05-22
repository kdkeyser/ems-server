package io.konektis

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.konektis.config.loadConfig
import io.konektis.config.WebSocketConfig
import io.konektis.devices.World
import io.konektis.di.AppComponent
import io.konektis.di.create
import io.konektis.ems.EMSState
import io.konektis.ems.EnergyManager
import io.ktor.server.application.*
import io.konektis.ocpp.configureOcppServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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

        logger.info(config)

        // Create the DI component
        val component = AppComponent::class.create(config)

        val dataCollector = component.dataCollector
        val energyManager = component.energyManager

        coroutineScope {
            launch {
                while (true) {
                    dataCollector.refresh()
                    delay(5000)
                }
            }
            //launch { energyManager.run(config) }
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(energyManager.emsStateFlow, config.websocket)
                }
                server.start(wait = true)
            }
        }

    }
}

fun Application.module(emsStateFlow: Flow<EMSState>, wsConfig: WebSocketConfig) {
    configureSecurity()
    configureAdministration()
    configureSockets(emsStateFlow, wsConfig)
    configureOcppServer()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
