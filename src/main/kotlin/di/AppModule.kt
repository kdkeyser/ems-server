package io.konektis.di

import io.konektis.DataCollector
import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.ems.EnergyManager
import io.konektis.ems.Strategy
import io.konektis.ems.SurplusPriorityStrategy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Provides

interface AppModule {

    @Provides
    fun provideHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10000
            connectTimeoutMillis = 5000
        }
    }

    @Provides
    fun provideWorld(config: Config): World = World.fromConfig(config)

    @Provides
    fun provideDataCollector(config: Config, world: World): DataCollector =
        DataCollector(config.refreshThreads, world)

    @Provides
    fun provideStrategy(): Strategy = SurplusPriorityStrategy()

    @Provides
    fun provideEnergyManager(world: World, config: Config, strategy: Strategy): EnergyManager =
        EnergyManager(world, config, strategy)
}
