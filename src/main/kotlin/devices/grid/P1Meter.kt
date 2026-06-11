package io.konektis.devices.grid

import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class P1MeterValues(
    val active_power_w: Float,
    val active_voltage_l1_v: Float,
    val active_voltage_l2_v: Float,
    val active_voltage_l3_v: Float,
)

private class P1MeterClient(private val host: String) {
    private var client: HttpClient = makeClient()

    private fun makeClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 3_000
            }
        }
    }

    suspend fun update(): DeviceUpdate<GridState>? {
        val response: HttpResponse = client.get("http://${host}/api/v1/data")
        if (response.status.isSuccess()) {
            val values: P1MeterValues = response.body()
            return DeviceUpdate(
                GlobalTimeSource.source.markNow(),
                GridState(
                    Watt(values.active_power_w.toInt()),
                    Volt(values.active_voltage_l1_v.toUInt())
                )
            )
        } else {
            client.close()
            client = makeClient()
            return null
        }
    }
}

class P1Meter(host: String,
              override val properties: GridProperties
) : Klogging, Grid {
    private val p1MeterClient = P1MeterClient(host)
    private var internalState: DeviceUpdate<GridState>? = null
    val mutex = Mutex()

    override suspend fun update() {
        mutex.withLock {
            internalState = p1MeterClient.update()
            logger.trace { "P1Meter: $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<GridState>? {
        mutex.withLock {
            return internalState
        }
    }
}
