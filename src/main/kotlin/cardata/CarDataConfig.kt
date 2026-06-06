package io.konektis.cardata

import kotlinx.serialization.Serializable

/**
 * BMW CarData integration config. The whole block is optional and disabled by default so existing
 * deployments load unchanged. When [enabled], [clientId], [vin] and [socDescriptor] must be non-blank.
 */
@Serializable
data class CarDataConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val vin: String = "",
    val socDescriptor: String = "",
    val brokerHost: String = "customer.streaming-cardata.bmwgroup.com",
    val brokerPort: Int = 9000,
) {
    fun validated(): CarDataConfig {
        if (enabled) {
            require(clientId.isNotBlank()) { "cardata.clientId must be set when cardata.enabled" }
            require(vin.isNotBlank()) { "cardata.vin must be set when cardata.enabled" }
            require(socDescriptor.isNotBlank()) { "cardata.socDescriptor must be set when cardata.enabled" }
        }
        return this
    }
}
