package io.konektis.cardata

import de.kempmobil.ktor.mqtt.MqttClient
import de.kempmobil.ktor.mqtt.buildFilterList
import io.klogging.Klogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Thin, swappable wrapper around ktor-mqtt. Subscribes to the vehicle's CarData stream
 * (`"<gcid>/<vin>"`) over TLS/MQTT5, decoding each PUBLISH payload to a string for [run]'s callback.
 * Owns its own token-aware reconnect loop: because the MQTT password *is* the ~1h ID token, it tears
 * down and reconnects with a freshly-minted token well before expiry.
 */
class CarDataMqttClient(
    private val config: CarDataConfig,
    private val auth: CarDataAuth,
) : Klogging {

    /** Connect, subscribe, and feed each message to [onMessage]; reconnects forever on drop/expiry. */
    suspend fun run(onMessage: (String) -> Unit) {
        while (true) {
            try {
                connectAndCollect(onMessage)
            } catch (e: Exception) {
                logger.warn("BMW CarData MQTT error: ${e.message}; reconnecting")
                delay(RECONNECT_BACKOFF)
            }
        }
    }

    private suspend fun connectAndCollect(onMessage: (String) -> Unit) {
        val gcid = auth.gcid()
        val idToken = auth.currentIdToken()
        val topic = "$gcid/${config.vin}"
        val client = MqttClient(config.brokerHost, config.brokerPort) {
            username = gcid
            password = idToken
            connection { tls { } }
        }
        try {
            val connack = client.connect().getOrThrow()
            check(connack.isSuccess) { "CarData broker refused connection: $connack" }
            client.subscribe(buildFilterList { +topic }).getOrThrow()
            logger.info("BMW CarData MQTT connected, subscribed to $topic")
            // Reconnect before the ID token expires so the next connect uses a fresh one.
            withTimeoutOrNull(SESSION_TTL) {
                client.publishedPackets.collect { publish ->
                    onMessage(publish.payload.toByteArray().decodeToString())
                }
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    companion object {
        private val SESSION_TTL = 50.minutes        // < the ~1h ID-token lifetime
        private val RECONNECT_BACKOFF = 10.seconds
    }
}
