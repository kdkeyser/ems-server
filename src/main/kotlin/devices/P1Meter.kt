package io.konektis.devices

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class P1Meter(val host: String) {

    @Serializable
    data class P1MeterValues(
        val active_power_w: Float,
        val active_voltage_l1_v: Float,
        val active_voltage_l2_v: Float,
        val active_voltage_l3_v: Float,
    )

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
        }
    }

    suspend fun update(): P1MeterValues? {

        val response: HttpResponse = client.get("http://${host}/api/v1/data")
        if (response.status.isSuccess()) {
            val value: P1MeterValues = response.body()
            return (value)
        } else {
            client.close()
            client = makeClient()
            return (null)
        }
    }
}