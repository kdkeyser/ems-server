package io.konektis.di

import io.konektis.DataCollector
import io.konektis.cardata.CarDataAuth
import io.konektis.cardata.CarDataMqttClient
import io.konektis.cardata.CarDataService
import io.konektis.cardata.CarDataTokenStore
import io.konektis.cardata.SqlCarDataTokenStore
import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.ems.EnergyManager
import io.konektis.ems.Strategy
import io.konektis.ems.SurplusPriorityStrategy
import io.konektis.ems.SimpleGridCompensationStrategy
import io.konektis.ocpp.OcppService
import io.konektis.ocpp.db.ChargePointStore
import io.konektis.ocpp.db.ChargerControlStore
import io.konektis.ocpp.db.IdTagStore
import io.konektis.ocpp.db.SqlChargerControlStore
import io.konektis.ocpp.db.TransactionStore
import io.konektis.ocpp.db.openDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Provides
import org.jetbrains.exposed.sql.Database

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

    @ApplicationScope
    @Provides
    fun provideWorld(config: Config, ocppService: OcppService): World = World.fromConfig(config, ocppService)

    @Provides
    fun provideDataCollector(config: Config, world: World): DataCollector =
        DataCollector(config.refreshThreads, world)

    @Provides
    fun provideStrategy(): Strategy = SurplusPriorityStrategy()

    @ApplicationScope
    @Provides
    fun provideChargerControlStore(database: Database): ChargerControlStore = SqlChargerControlStore(database)

    @ApplicationScope
    @Provides
    fun provideEnergyManager(
        world: World, config: Config, strategy: Strategy,
        chargerControlStore: ChargerControlStore, carDataService: CarDataService,
    ): EnergyManager = EnergyManager(world, config, strategy, chargerControlStore, carDataService)

    @ApplicationScope
    @Provides
    fun provideDatabase(config: Config): Database = openDatabase(config.database.path)

    @ApplicationScope
    @Provides
    fun provideOcppService(config: Config, database: Database): OcppService =
        OcppService(
            ChargePointStore(database), IdTagStore(database),
            TransactionStore(database), config.ocpp,
        ).also { it.initStores() }

    @ApplicationScope
    @Provides
    fun provideCarDataTokenStore(database: Database): CarDataTokenStore =
        SqlCarDataTokenStore(database).also { it.init() }

    @ApplicationScope
    @Provides
    fun provideCarDataService(
        config: Config, tokenStore: CarDataTokenStore, httpClient: HttpClient,
    ): CarDataService {
        val cfg = config.cardata.validated()
        val auth = CarDataAuth(cfg, tokenStore, httpClient)
        val mqtt = CarDataMqttClient(cfg, auth)
        return CarDataService(cfg, tokenStore, auth, mqtt)
    }
}
