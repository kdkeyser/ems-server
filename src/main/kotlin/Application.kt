package io.konektis

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.ktor.server.application.*
import kotlinx.coroutines.coroutineScope
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

        val config = ConfigLoaderBuilder.default()
            .addResourceSource("/config.yaml")
            .build()
            .loadConfigOrThrow<Config>()

        logger.info(config)
        val energyManager = EnergyManager()

        coroutineScope {
            launch { energyManager.run(config.energyManager) }
            launch { io.ktor.server.netty.EngineMain.main(args) }
        }

    }
}

fun Application.module() {
   // configureSecurity()
    configureAdministration()
    configureSockets()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
