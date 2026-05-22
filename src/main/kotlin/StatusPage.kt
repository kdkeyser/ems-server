package io.konektis

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.configureStatusPage(statusFlow: Flow<StatusState?>) {
    routing {
        get("/status") {
            val bytes = object {}::class.java.getResourceAsStream("/status.html")
                ?.readBytes()
                ?: error("status.html not found in classpath")
            call.respondBytes(bytes, ContentType.Text.Html)
        }
        webSocket("/status-ws") {
            statusFlow.filterNotNull().collect { state ->
                send(Json.encodeToString(state))
            }
        }
    }
}
