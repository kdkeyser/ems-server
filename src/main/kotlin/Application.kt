package io.konektis

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.konektis.config.loadConfig
import io.konektis.ems.EMSState
import io.konektis.ems.EnergyManager
import io.ktor.server.application.*
import io.konektis.ocpp.configureOcppServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.coroutineScope
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
        val energyManager = EnergyManager()

        coroutineScope {
            launch { energyManager.run(config.energyManager) }
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(energyManager.emsStateFlow)
                }
                server.start(wait = true)
            }
        }

    }
}

fun Application.module(emsStateFlow: Flow<EMSState>) {
    configureSecurity()
    configureAdministration()
    configureSockets(emsStateFlow)
    configureOcppServer()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
