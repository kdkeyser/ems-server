package io.konektis.di

import io.konektis.DataCollector
import io.konektis.cardata.CarDataAuth
import io.konektis.cardata.CarDataConfig
import io.konektis.cardata.CarDataEndpoints
import io.konektis.cardata.CarDataMqttClient
import io.konektis.cardata.CarDataService
import io.konektis.cardata.CarDataTokenStore
import io.konektis.cardata.SqlCarDataTokenStore
import io.konektis.config.Config
import io.konektis.config.ConfigService
import io.konektis.config.StrategyType
import io.konektis.devices.World
import io.konektis.devices.WorldHolder
import io.konektis.ems.EnergyManager
import io.konektis.ems.Strategy
import io.konektis.ems.SurplusPriorityStrategy
import io.konektis.ems.SimpleGridCompensationStrategy
import io.konektis.history.HistoryRepository
import io.konektis.history.HistoryWriter
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
    fun provideWorldHolder(config: Config, ocppService: OcppService): WorldHolder =
        WorldHolder(World.fromConfig(config, ocppService))

    @Provides
    fun provideDataCollector(config: Config, worldHolder: WorldHolder): DataCollector =
        DataCollector(config.refreshThreads, worldHolder)

    @Provides
    fun provideStrategy(config: Config): Strategy = when (config.strategy) {
        StrategyType.SurplusPriority -> SurplusPriorityStrategy()
        StrategyType.SimpleGridCompensation -> SimpleGridCompensationStrategy()
    }

    @ApplicationScope
    @Provides
    fun provideChargerControlStore(database: Database): ChargerControlStore = SqlChargerControlStore(database)

    @ApplicationScope
    @Provides
    fun provideEnergyManager(
        worldHolder: WorldHolder, configService: ConfigService, strategy: Strategy,
        chargerControlStore: ChargerControlStore, carDataService: CarDataService,
    ): EnergyManager = EnergyManager(worldHolder, configService::current, strategy, chargerControlStore, carDataService)

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
        val car = config.devices.car.firstOrNull()
        val cfg = if (car != null) CarDataConfig(
            enabled = car.enabled,
            clientId = car.clientId,
            vin = car.vin,
            socDescriptor = CarDataEndpoints.SOC_DESCRIPTOR,
            brokerHost = car.brokerHost,
            brokerPort = car.brokerPort,
        ).validated() else CarDataConfig()
        val auth = CarDataAuth(cfg, tokenStore, httpClient)
        val mqtt = CarDataMqttClient(cfg, auth)
        return CarDataService(cfg, tokenStore, auth, mqtt)
    }

    @ApplicationScope
    @Provides
    fun provideHistoryWriter(config: Config): HistoryWriter {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
        }
        return HistoryWriter(config.clickhouse, client)
    }

    @ApplicationScope
    @Provides
    fun provideHistoryRepository(config: Config): HistoryRepository {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 }
        }
        return HistoryRepository(config.clickhouse, client)
    }
}
