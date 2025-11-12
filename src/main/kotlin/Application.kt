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
    coroutineScope {
        launch { Main().main() }
        launch { io.ktor.server.netty.EngineMain.main(args) }
    }
}

class Main : Klogging {
    suspend fun main() {
        loggingConfiguration {
            sink("stdout", RENDER_SIMPLE, STDOUT)
            logging {
                fromMinLevel(Level.TRACE) {
                    toSink("stdout")
                }
            }
        }

        val config = ConfigLoaderBuilder.default().addResourceSource("/config.yaml").build().loadConfigOrThrow<Config>()
        logger.info(config)
        val energyManager = EnergyManager()
        energyManager.run(config.energyManager)
    }
}

fun Application.module() {

    configureSerialization()
    configureSecurity()
    configureAdministration()
    configureSockets()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
