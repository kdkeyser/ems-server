package io.konektis

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        webSocket("/ws") {
            var authenticated = false

            val job = launch {
                while (true) {
                    delay(5.seconds)
                    if (authenticated) {
                        val message = randomPowerUsageUpdate()
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
