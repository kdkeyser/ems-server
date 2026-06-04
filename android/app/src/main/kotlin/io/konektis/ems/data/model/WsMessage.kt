package io.konektis.ems.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// NOTE: @SerialName on every sealed subclass MUST match the server's Messages.kt. Without it,
// kotlinx-serialization derives the polymorphic "type" discriminator from the fully-qualified
// class name, which differs between this package (io.konektis.ems.data.model) and the server
// (io.konektis) — control messages would then fail to decode server-side.

@Serializable
sealed class ChargingState {
    @Serializable @SerialName("NotCharging") data object NotCharging : ChargingState()
    @Serializable @SerialName("ChargingWithExcessPower") data object ChargingWithExcessPower : ChargingState()
    @Serializable @SerialName("ChargingWithMaxPower") data class ChargingWithMaxPower(val maxPower: UInt) : ChargingState()
}

@Serializable
enum class Devices { SOLAR, BATTERY, CAR_CHARGER, HEATPUMP, GRID }

@Serializable
enum class ManagerMode { AUTO, MANUAL }

@Serializable
data class Update(val device: Devices, val power: Int)

@Serializable
sealed class Message {
    @Serializable @SerialName("PowerUsageUpdate") data class PowerUsageUpdate(val updates: List<Update>) : Message()
    @Serializable @SerialName("Authenticated") data class Authenticated(val username: String) : Message()
    @Serializable @SerialName("Unauthorized") data class Unauthorized(val username: String) : Message()
    @Serializable @SerialName("ModeUpdate") data class ModeUpdate(val mode: ManagerMode) : Message()
    @Serializable @SerialName("ChargingStateUpdate") data class ChargingStateUpdate(val chargingState: ChargingState) : Message()
}

@Serializable
sealed class ClientMessage {
    @Serializable @SerialName("SetCharging") data class SetCharging(val chargingState: ChargingState) : ClientMessage()
    @Serializable @SerialName("Authenticate") data class Authenticate(val username: String, val password: String) : ClientMessage()
    @Serializable @SerialName("SetMode") data class SetMode(val mode: ManagerMode) : ClientMessage()
}
