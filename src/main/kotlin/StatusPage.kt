package io.konektis

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
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
        authenticate("auth-basic") {
            get("/status") {
                val bytes = object {}::class.java.getResourceAsStream("/status.html")!!.readBytes()
                call.respondBytes(bytes, ContentType.Text.Html)
            }
        }
        // Read-only telemetry; browser JS cannot send Basic credentials on a WS upgrade.
        webSocket("/status-ws") {
            statusFlow.filterNotNull().collect { state ->
                send(Json.encodeToString(state))
            }
        }
    }
}
