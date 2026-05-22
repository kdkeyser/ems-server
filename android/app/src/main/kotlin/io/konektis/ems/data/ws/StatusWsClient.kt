package io.konektis.ems.data.ws

import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

class StatusWsClient(
    private val settings: SettingsRepository,
    private val client: HttpClient
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val statusFlow: Flow<StatusState> = settings.settingsFlow.transformLatest { s ->
        if (s.serverUrl.isBlank()) {
            _connectionState.value = ConnectionState.Disconnected()
            return@transformLatest
        }
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnectionState.Connecting
            try {
                client.webSocket("ws://${s.serverUrl}/status-ws") {
                    attempt = 0
                    _connectionState.value = ConnectionState.Connected
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            emit(Json.decodeFromString<StatusState>(frame.readText()))
                        }
                    }
                    _connectionState.value = ConnectionState.Disconnected()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Disconnected(e.message)
            }
            delay(BACKOFF[minOf(attempt++, BACKOFF.size - 1)])
        }
    }

    companion object {
        private val BACKOFF = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
