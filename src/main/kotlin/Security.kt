package io.konektis

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.konektis.config.WebSocketConfig

fun Application.configureSecurity(wsConfig: WebSocketConfig) {
    authentication {
        basic(name = "auth-basic") {
            realm = "EMS"
            validate { credentials ->
                if (credentials.name == wsConfig.username && credentials.password == wsConfig.password) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
}
