package io.konektis

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.klogging.logger
import io.konektis.ems.EMSState
import kotlinx.coroutines.flow.Flow

fun Application.configureSockets(emsStateFlow: Flow<EMSState>) {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    val logger = logger("io.konektis.sockets")
    routing {
        webSocket("/ws") {

            var authenticated = false

            val job = launch {
                emsStateFlow.collect { emsState ->
                    if (authenticated) {
                        val l = ArrayList<Update>()
                        if (emsState.gridPower != null) {
                            l.add(Update(Devices.GRID, emsState.gridPower))
                        }
                        if (emsState.chargerPower != null) {
                            l.add(Update(Devices.CAR_CHARGER, emsState.chargerPower))
                        }
                        if (emsState.heatpumpPower != null) {
                            l.add(Update(Devices.HEATPUMP, emsState.heatpumpPower))
                        }
                        if (emsState.solarPower != null) {
                            l.add(Update(Devices.SOLAR, emsState.solarPower))
                        }
                        if (emsState.batteryPower != null) {
                            l.add(Update(Devices.BATTERY, emsState.batteryPower))
                        }
                        val message = Message.PowerUsageUpdate(l)
                        logger.debug("Sending $message")
                        send(Json.encodeToString(message as Message))

                    }
                }
            }

            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        when (val message = deserializeClientMessage(text)) {
                            is ClientMessage.Authenticate -> {
                                if (message.username == "user" && message.password == "password") {
                                    authenticated = true
                                    send(Json.encodeToString(Message.Authenticated(message.username) as Message))
                                } else {
                                    send(Json.encodeToString(Message.Unauthorized(message.username) as Message))
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                                }
                            }
                            else -> {
                                if (!authenticated) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Invalid Message"))
                    }
                }
            }
        }
    }
}
