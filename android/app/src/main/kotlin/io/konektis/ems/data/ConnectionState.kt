package io.konektis.ems.data

sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Disconnected(val error: String? = null) : ConnectionState()
}

sealed class ControlState {
    object Connecting : ControlState()
    object Authenticated : ControlState()
    object Unauthenticated : ControlState()
    data class Disconnected(val error: String? = null) : ControlState()
}
