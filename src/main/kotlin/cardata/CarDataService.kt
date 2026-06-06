package io.konektis.cardata

import io.klogging.Klogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    fun onMessage(payload: String) {
        val soc = parseSoc(payload, config.socDescriptor) ?: return
        _socFlow.value = soc
    }

    /** Bootstrap auth (one-time device approval), then stream SoC with token-aware reconnect. No-op when disabled. */
    suspend fun start() {
        if (!config.enabled) return
        auth.ensureAuthorized()
        mqtt.run(::onMessage)
    }
}
