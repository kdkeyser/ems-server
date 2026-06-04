package io.konektis.ocpp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class IdTagBody(val idTag: String, val status: String)
@Serializable data class AcceptedBody(val accepted: Boolean)
@Serializable data class StartBody(val idTag: String, val connectorId: Int? = null)
@Serializable data class StopBody(val transactionId: Int)
@Serializable data class ResetBody(val type: String = "Soft")
@Serializable data class SetCurrentBody(val amps: Double, val connectorId: Int = 1)
@Serializable data class ClearProfileBody(val connectorId: Int? = null)

fun Application.configureOcppWebUi(service: OcppService) {
    val json = Json { encodeDefaults = true }
    routing {
        get("/ocpp-ui") {
            val bytes = object {}::class.java.getResourceAsStream("/ocpp.html")!!.readBytes()
            call.respondBytes(bytes, ContentType.Text.Html)
        }

        // Live status push.
        webSocket("/ocpp-ui/ws") {
            service.stateFlow.collect { send(Json.encodeToString(it)) }
        }

        route("/ocpp-ui/api") {
            get("/state") { call.respondText(Json.encodeToString(service.stateFlow.value), ContentType.Application.Json) }

            get("/chargepoints") { call.respondText(json.encodeToString(service.listChargePoints()), ContentType.Application.Json) }
            post("/chargepoints/{id}/accepted") {
                val id = call.parameters["id"]!!
                val body = call.receive<AcceptedBody>()
                service.setChargePointAccepted(id, body.accepted)
                call.respond(HttpStatusCode.OK)
            }

            get("/idtags") { call.respondText(json.encodeToString(service.listIdTags()), ContentType.Application.Json) }
            post("/idtags") {
                val body = call.receive<IdTagBody>()
                service.putIdTag(body.idTag, body.status)
                call.respond(HttpStatusCode.OK)
            }
            delete("/idtags/{idTag}") {
                service.deleteIdTag(call.parameters["idTag"]!!)
                call.respond(HttpStatusCode.OK)
            }

            get("/transactions") {
                call.respondText(json.encodeToString(service.recentTransactions(50)), ContentType.Application.Json)
            }

            // Manual actions.
            post("/chargepoints/{id}/start") {
                val body = call.receive<StartBody>()
                val ok = service.remoteStart(call.parameters["id"]!!, body.idTag, body.connectorId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            post("/chargepoints/{id}/stop") {
                val body = call.receive<StopBody>()
                val ok = service.remoteStop(call.parameters["id"]!!, body.transactionId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            post("/chargepoints/{id}/reset") {
                val body = call.receive<ResetBody>()
                val type = runCatching { ResetType.valueOf(body.type) }.getOrDefault(ResetType.Soft)
                val ok = service.reset(call.parameters["id"]!!, type)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            // Manual current override — bypasses the per-charger EMS-auto gate (operator action).
            post("/chargepoints/{id}/set-current") {
                val body = call.receive<SetCurrentBody>()
                val ok = service.setChargingProfile(
                    call.parameters["id"]!!, body.connectorId, body.amps, ChargingRateUnitType.A
                )
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            // Clear charging profiles (e.g. to unstick a charger left at 0 A).
            post("/chargepoints/{id}/clear-profile") {
                val body = call.receive<ClearProfileBody>()
                val ok = service.clearChargingProfile(call.parameters["id"]!!, body.connectorId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
        }
    }
}
