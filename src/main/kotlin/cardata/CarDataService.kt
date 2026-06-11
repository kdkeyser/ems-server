package io.konektis.cardata

import io.klogging.Klogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Owns the BMW CarData connection and exposes the latest car State-of-Charge as [socFlow].
 * Modelled on OcppService: a DI singleton with its own lifecycle. Display-only; nothing here
 * feeds control logic. The MQTT/auth IO is wired in [start] (later task); [onMessage] is the pure,
 * unit-tested update path.
 */
class CarDataService(
    private val config: CarDataConfig,
    private val tokenStore: CarDataTokenStore,
    private val auth: CarDataAuth,
    private val mqtt: CarDataMqttClient,
) : Klogging {

    private val _socFlow = MutableStateFlow<Int?>(null)
    val socFlow: StateFlow<Int?> = _socFlow.asStateFlow()

    /** Parse a raw MQTT payload; update [socFlow] only when it yields a SoC. */
    suspend fun onMessage(payload: String) {
        logDescriptor(payload)
        if (!payload.contains(config.socDescriptor)) return
        val soc = parseSoc(payload, config.socDescriptor) ?: run {
            logger.warn("BMW CarData: could not parse SoC from payload (descriptor=${config.socDescriptor}): $payload")
            return
        }
        logger.debug("BMW CarData SoC updated: $soc%")
        _socFlow.value = soc
    }

    private suspend fun logDescriptor(payload: String) {
        if (!logger.isDebugEnabled()) return
        val descriptor = try {
            Json.parseToJsonElement(payload).jsonObject["data"]?.jsonObject?.keys?.firstOrNull() ?: "?"
        } catch (_: Exception) { "?" }
        logger.debug("BMW CarData descriptor: $descriptor")
    }

    /** Bootstrap auth (one-time device approval), then stream SoC with token-aware reconnect. No-op when disabled.
     *  Never throws (except cancellation): this runs as a sibling of the EMS control loops in one
     *  coroutineScope, and an MQTT/auth failure must not cancel battery/charger control. */
    suspend fun start() {
        if (!config.enabled) return
        try {
            auth.ensureAuthorized()
            mqtt.run(::onMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e, "BMW CarData stopped: ${e.message} — car SoC will be unavailable")
        }
    }
}
