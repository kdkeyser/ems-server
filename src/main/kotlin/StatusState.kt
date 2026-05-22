// src/main/kotlin/StatusState.kt
package io.konektis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DeviceHealth {
    @Serializable
    @SerialName("online")
    data class Online(
        val lastSeenAt: Long,          // epoch milliseconds
        val powerW: Int,
        val extraInfo: String? = null  // e.g. "62% SoC" for battery
    ) : DeviceHealth()

    @Serializable
    @SerialName("offline")
    data class Offline(
        val lastSeenAt: Long? = null,  // null if device was never reached
        val lastError: String? = null
    ) : DeviceHealth()
}

@Serializable
data class DeviceStatus(
    val name: String,
    val health: DeviceHealth
)

@Serializable
data class StatusState(
    val devices: List<DeviceStatus>,
    val totalSolarW: Int?,
    val gridW: Int?,
    val batteryW: Int?,
    val batteryCharge: Int?,
    val chargerW: Int?,
    val heatpumpW: Int?
)
