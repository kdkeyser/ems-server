package io.konektis.ems.data.ws

import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.Message
import io.konektis.ems.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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

    // Current EMS mode as reported by the server (null = unknown / not yet received).
    private val _mode = MutableStateFlow<ManagerMode?>(null)
    val mode: StateFlow<ManagerMode?> = _mode.asStateFlow()

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
                        client.webSocket(
                            urlString = wsUrl(s.serverUrl, s.useTls, "/ws"),
                            request = {
                                // TODO(task-2): wire CF Access headers (cfAccessClientId / cfAccessClientSecret)
                            }
                        ) {
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
                                    when (val msg = Json.decodeFromString<Message>(frame.readText())) {
                                        is Message.Authenticated ->
                                            _connectionState.value = ControlState.Authenticated
                                        is Message.Unauthorized -> {
                                            _connectionState.value = ControlState.Unauthenticated
                                            _mode.value = null
                                            cmdJob.cancel()
                                            return@webSocket
                                        }
                                        is Message.ModeUpdate -> _mode.value = msg.mode
                                        else -> Unit
                                    }
                                }
                            }
                            cmdJob.cancel()
                            _connectionState.value = ControlState.Disconnected()
                            _mode.value = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _connectionState.value = ControlState.Disconnected(e.message)
                        _mode.value = null
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

    suspend fun setMode(mode: ManagerMode) = send(ClientMessage.SetMode(mode))
}
