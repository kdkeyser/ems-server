package io.konektis.cardata

/** Thin, swappable wrapper around the MQTT library. Real implementation in a later task. */
class CarDataMqttClient(
    private val config: CarDataConfig,
    private val auth: CarDataAuth,
) {
    // Later task: run(onMessage: (String) -> Unit) with TLS/MQTT5 connect + token-aware reconnect.
}
