package io.konektis.cardata

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import io.klogging.Klogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Thin, swappable wrapper around the HiveMQ MQTT 5 client. Subscribes to the vehicle's CarData
 * stream (`"<gcid>/<vin>"`) over TLS/MQTT5, decoding each PUBLISH payload to a string for [run]'s
 * callback. Uses Java's SSLEngine (via Netty) so TLS 1.3 works out of the box.
 *
 * Owns its own token-aware reconnect loop: tears down and reconnects with a freshly-minted ID
 * token well before the ~1h token expiry.
 */
class CarDataMqttClient(
    private val config: CarDataConfig,
    private val auth: CarDataAuth,
) : Klogging {

    /** Connect, subscribe, and feed each message to [onMessage]; reconnects forever on drop/expiry. */
    suspend fun run(onMessage: suspend (String) -> Unit) {
        while (true) {
            try {
                connectAndCollect(onMessage)
            } catch (e: Exception) {
                logger.warn(e, "BMW CarData MQTT error: ${e.message}; reconnecting")
                delay(RECONNECT_BACKOFF)
            }
        }
    }

    private suspend fun connectAndCollect(onMessage: suspend (String) -> Unit) {
        val gcid = auth.gcid()
        val idToken = auth.currentIdToken()
        val topic = "$gcid/${config.vin}"

        val client = MqttClient.builder()
            .useMqttVersion5()
            .serverHost(config.brokerHost)
            .serverPort(config.brokerPort)
            .sslWithDefaultConfig()
            .buildBlocking()

        try {
            client.connectWith()
                .simpleAuth()
                    .username(gcid)
                    .password(idToken.toByteArray())
                    .applySimpleAuth()
                .cleanStart(true)
                .keepAlive(30)
                .send()

            logger.info("BMW CarData MQTT connected, subscribed to $topic")

            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .send()

            // Reconnect before the ID token expires so the next connect uses a fresh one.
            // publishes.next() is blocking; runInterruptible lets coroutine cancellation unblock it.
            client.publishes(MqttGlobalPublishFilter.SUBSCRIBED).use { publishes ->
                withTimeoutOrNull(SESSION_TTL) {
                    while (true) {
                        val publish = runInterruptible(Dispatchers.IO) { publishes.receive() }
                        publish.payloadAsBytes?.decodeToString()?.let { onMessage(it) }
                    }
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
