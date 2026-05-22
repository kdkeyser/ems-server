package io.konektis.ems.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DeviceHealth {
    @Serializable @SerialName("online")
    data class Online(
        val lastSeenAt: Long,
        val powerW: Int,
        val extraInfo: String? = null
    ) : DeviceHealth()

    @Serializable @SerialName("offline")
    data class Offline(
        val lastSeenAt: Long? = null,
        val lastError: String? = null
    ) : DeviceHealth()
}

@Serializable
data class DeviceStatus(
    val name: String,
    val health: DeviceHealth,
    val category: String
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
