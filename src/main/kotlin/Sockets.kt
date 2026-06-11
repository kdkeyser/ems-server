package io.konektis

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import io.klogging.logger
import io.konektis.ems.EnergyManager
import io.konektis.config.WebSocketConfig

fun Application.configureSockets(energyManager: EnergyManager, wsConfig: WebSocketConfig) {
    val emsStateFlow = energyManager.emsStateFlow
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    val logger = logger("io.konektis.sockets")
    routing {
        webSocket("/ws") {

            // Shared across the collector coroutines below, which may run on different threads.
            val authenticated = java.util.concurrent.atomic.AtomicBoolean(false)

            val job = launch {
                emsStateFlow.collect { emsState ->
                    if (authenticated.get()) {
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

            // Push EMS AUTO/MANUAL mode to the client whenever it changes (once authenticated).
            // The current value is also sent explicitly on successful auth below, because this
            // collector's initial emission is dropped while authenticated is still false.
            val modeJob = launch {
                energyManager.modeFlow.collect { mode ->
                    if (authenticated.get()) {
                        send(Json.encodeToString(Message.ModeUpdate(mode) as Message))
                    }
                }
            }

            val chargingJob = launch {
                energyManager.chargerControlFlow.collect { control ->
                    if (authenticated.get()) {
                        send(Json.encodeToString(Message.ChargerControlUpdate(control) as Message))
                    }
                }
            }

            // Push the car's SoC (%) whenever it changes (once authenticated). Like mode/charger
            // control, the current value is also sent explicitly on auth below (initial emission dropped).
            val carJob = launch {
                energyManager.carSocFlow.collect { soc ->
                    if (authenticated.get()) {
                        send(Json.encodeToString(Message.CarStateUpdate(soc) as Message))
                    }
                }
            }

            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        when (val message = deserializeClientMessage(text)) {
                            is ClientMessage.Authenticate -> {
                                if (message.username == wsConfig.username && message.password == wsConfig.password) {
                                    authenticated.set(true)
                                    send(Json.encodeToString(Message.Authenticated(message.username) as Message))
                                    // Send the current mode immediately so the client reflects it on connect.
                                    send(Json.encodeToString(Message.ModeUpdate(energyManager.modeFlow.value) as Message))
                                    send(Json.encodeToString(Message.ChargerControlUpdate(energyManager.chargerControlFlow.value) as Message))
                                    send(Json.encodeToString(Message.CarStateUpdate(energyManager.carSocFlow.value) as Message))
                                } else {
                                    delay(1000) // slow down brute-force attempts
                                    send(Json.encodeToString(Message.Unauthorized(message.username) as Message))
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                                }
                            }
                            is ClientMessage.SetMode -> {
                                if (!authenticated.get()) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                } else {
                                    // The modeJob collector echoes the resulting ModeUpdate; no explicit send here.
                                    energyManager.setMode(
                                        if (message.mode == ManagerMode.AUTO) io.konektis.ems.Mode.AUTO else io.konektis.ems.Mode.MANUAL
                                    )
                                }
                            }
                            is ClientMessage.SetCharging -> {
                                if (!authenticated.get()) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                } else {
                                    energyManager.setCharging(message.control)
                                }
                            }
                            else -> {
                                if (!authenticated.get()) {
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
