package io.konektis.di

import io.konektis.DataCollector
import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.ems.EnergyManager
import io.konektis.ocpp.OcppService
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import org.jetbrains.exposed.sql.Database

@ApplicationScope
@Component
abstract class AppComponent(
    @get:Provides val config: Config
) : AppModule {
    abstract val httpClient: HttpClient
    abstract val world: World
    abstract val dataCollector: DataCollector
    abstract val energyManager: EnergyManager
    abstract val database: Database
    abstract val ocppService: OcppService

    companion object
}
