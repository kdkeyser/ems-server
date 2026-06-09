package io.konektis

import io.konektis.config.WebSocketConfig
import io.ktor.client.request.get
import io.ktor.client.request.basicAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.*

class SecurityAuthTest {
    private fun io.ktor.server.testing.ApplicationTestBuilder.appWith(cfg: WebSocketConfig) = application {
        configureSecurity(cfg)
        routing { authenticate("auth-basic") { get("/secret") { call.respondText("ok") } } }
    }

    @Test
    fun acceptsConfiguredCredentials() = testApplication {
        appWith(WebSocketConfig("alice", "s3cret"))
        val resp = client.get("/secret") { basicAuth("alice", "s3cret") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun rejectsWrongCredentials() = testApplication {
        appWith(WebSocketConfig("alice", "s3cret"))
        val resp = client.get("/secret") { basicAuth("user", "password") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
