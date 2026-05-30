package io.konektis.ems.data.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ChargingState {
    @Serializable data object NotCharging : ChargingState()
    @Serializable data object ChargingWithExcessPower : ChargingState()
    @Serializable data class ChargingWithMaxPower(val maxPower: UInt) : ChargingState()
}

@Serializable
enum class Devices { SOLAR, BATTERY, CAR_CHARGER, HEATPUMP, GRID }

@Serializable
data class Update(val device: Devices, val power: Int)

@Serializable
sealed class Message {
    @Serializable data class PowerUsageUpdate(val updates: List<Update>) : Message()
    @Serializable data class Authenticated(val username: String) : Message()
    @Serializable data class Unauthorized(val username: String) : Message()
}

@Serializable
sealed class ClientMessage {
    @Serializable data class SetCharging(val chargingState: ChargingState) : ClientMessage()
    @Serializable data class Authenticate(val username: String, val password: String) : ClientMessage()
}
