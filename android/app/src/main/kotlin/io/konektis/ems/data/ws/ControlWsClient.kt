package io.konektis.ems.data.ws

import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.Message
import io.konektis.ems.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ControlWsClient(
    private val settings: SettingsRepository,
    private val client: HttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow<ControlState>(ControlState.Connecting)
    val connectionState: StateFlow<ControlState> = _connectionState.asStateFlow()

    private val commandChannel = Channel<ClientMessage>(Channel.BUFFERED)

    init {
        scope.launch {
            settings.settingsFlow.collectLatest { s ->
                if (s.serverUrl.isBlank()) {
                    _connectionState.value = ControlState.Disconnected()
                    return@collectLatest
                }
                var attempt = 0
                while (currentCoroutineContext().isActive) {
                    _connectionState.value = ControlState.Connecting
                    try {
                        client.webSocket("ws://${s.serverUrl}/ws") {
                            attempt = 0
                            outgoing.send(Frame.Text(
                                Json.encodeToString<ClientMessage>(
                                    ClientMessage.Authenticate(s.username, s.password)
                                )
                            ))
                            val cmdJob = launch {
                                for (cmd in commandChannel) {
                                    outgoing.send(Frame.Text(Json.encodeToString<ClientMessage>(cmd)))
                                }
                            }
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    when (Json.decodeFromString<Message>(frame.readText())) {
                                        is Message.Authenticated ->
                                            _connectionState.value = ControlState.Authenticated
                                        is Message.Unauthorized -> {
                                            _connectionState.value = ControlState.Unauthenticated
                                            cmdJob.cancel()
                                            return@webSocket
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            cmdJob.cancel()
                            _connectionState.value = ControlState.Disconnected()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _connectionState.value = ControlState.Disconnected(e.message)
                    }
                    if (_connectionState.value is ControlState.Unauthenticated) break
                    delay(WS_BACKOFF[minOf(attempt++, WS_BACKOFF.size - 1)])
                }
            }
        }
    }

    suspend fun send(command: ClientMessage) {
        check(_connectionState.value is ControlState.Authenticated) { "Not authenticated" }
        commandChannel.send(command)
    }
}
