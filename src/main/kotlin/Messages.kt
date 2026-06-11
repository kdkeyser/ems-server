package io.konektis

import kotlinx.serialization.*
import kotlinx.serialization.json.*

// NOTE: every sealed subclass below pins an explicit @SerialName. kotlinx-serialization
// otherwise defaults the polymorphic "type" discriminator to the fully-qualified class name,
// which differs between this server (io.konektis) and the Android app
// (io.konektis.ems.data.model) and would make cross-process control messages fail to decode.
// These names MUST stay in sync with the app's WsMessage.kt.

@Serializable
enum class ChargerMode { SOLAR, FIXED }

@Serializable
data class ChargerControl(
    val mode: ChargerMode = ChargerMode.SOLAR,
    val fixedAmps: Int = 16,
    val charging: Boolean = true,
)

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
    @Serializable @SerialName("ChargerControlUpdate")
    data class ChargerControlUpdate(val control: ChargerControl) : Message()
    @Serializable @SerialName("CarStateUpdate")
    data class CarStateUpdate(val soc: Int?) : Message()
}

@Serializable
sealed class ClientMessage {
    // Messages from client
    @Serializable @SerialName("SetCharging")
    data class SetCharging(val control: ChargerControl) : ClientMessage()
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
