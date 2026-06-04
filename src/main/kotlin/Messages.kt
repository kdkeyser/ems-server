package io.konektis

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.random.Random

// NOTE: every sealed subclass below pins an explicit @SerialName. kotlinx-serialization
// otherwise defaults the polymorphic "type" discriminator to the fully-qualified class name,
// which differs between this server (io.konektis) and the Android app
// (io.konektis.ems.data.model) and would make cross-process control messages fail to decode.
// These names MUST stay in sync with the app's WsMessage.kt.

@Serializable
sealed class ChargingState {
    @Serializable @SerialName("NotCharging")
    class NotCharging() : ChargingState()

    @Serializable @SerialName("ChargingWithExcessPower")
    class ChargingWithExcessPower() : ChargingState()

    @Serializable @SerialName("ChargingWithMaxPower")
    data class ChargingWithMaxPower(val maxPower: UInt) : ChargingState()
}

@Serializable
enum class Devices {
    SOLAR,
    BATTERY,
    CAR_CHARGER,
    HEATPUMP,
    GRID
}

@Serializable
// power: negative = producing/exporting, positive = consuming/importing
data class Update(val device: Devices, val power: Int)

@Serializable
enum class ManagerMode { AUTO, MANUAL }

@Serializable
sealed class Message {
    // Messages from server
    @Serializable @SerialName("PowerUsageUpdate")
    data class PowerUsageUpdate(val updates: List<Update>) : Message()
    @Serializable @SerialName("Authenticated")
    data class Authenticated(val username: String) : Message()
    @Serializable @SerialName("Unauthorized")
    data class Unauthorized(val username: String) : Message()
    @Serializable @SerialName("ModeUpdate")
    data class ModeUpdate(val mode: ManagerMode) : Message()
    @Serializable @SerialName("ChargingStateUpdate")
    data class ChargingStateUpdate(val chargingState: ChargingState) : Message()
}

@Serializable
sealed class ClientMessage {
    // Messages from client
    @Serializable @SerialName("SetCharging")
    data class SetCharging(val chargingState: ChargingState) : ClientMessage()
    @Serializable @SerialName("Authenticate")
    data class Authenticate(val username : String, val password: String) : ClientMessage()
    @Serializable @SerialName("SetMode")
    data class SetMode(val mode: ManagerMode) : ClientMessage()
}

fun deserializeMessage(json: String): Message {
    return Json.decodeFromString<Message>(json)
}

fun deserializeClientMessage(json: String): ClientMessage {
    return Json.decodeFromString<ClientMessage>(json)
}
fun randomPowerUsageUpdate(): Message {
    return Message.PowerUsageUpdate(
        listOf(
            Update(Devices.SOLAR, Random.nextInt(-5000, 0)),
            Update(Devices.BATTERY, Random.nextInt(-5000, 5000)),
            Update(Devices.CAR_CHARGER, Random.nextInt(0, 7500)),
            Update(Devices.HEATPUMP, Random.nextInt(0, 5000)),
            Update(Devices.GRID, Random.nextInt(-5000, 5000))
        ))
}
