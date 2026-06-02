package io.konektis.ems.data.ws

/**
 * Builds a WebSocket URL from a user-entered server address.
 *
 * Accepts a bare `host` or `host:port` (the remote case is a bare host like
 * `ec29.ems.konektis.io`; the LAN case includes a port). Any scheme the user typed
 * (`http(s)://`, `ws(s)://`) is stripped and replaced based on [useTls].
 */
internal fun wsUrl(serverUrl: String, useTls: Boolean, path: String): String {
    val host = serverUrl
        .substringAfter("://")     // drop any scheme the user typed
        .trim()
        .trimEnd('/')
    val scheme = if (useTls) "wss" else "ws"
    return "$scheme://$host$path"
}
