package io.konektis.ocpp

import io.klogging.logger
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.*

private const val OCPP_SUBPROTOCOL = "ocpp1.6"

/** Wire the OCPP charge-point WebSocket endpoint. WebSockets plugin is installed in configureSockets. */
fun Application.configureOcppServer(service: OcppService) {
    val handler = OcppMessageHandler(service)
    routing {
        // Same handler on both paths: some chargers append the OCPP version to the URL.
        listOf("/ocpp/{chargePointId}", "/ocpp/1.6/{chargePointId}").forEach { path ->
            webSocket(path, protocol = OCPP_SUBPROTOCOL) {
                val chargePointId = call.parameters["chargePointId"] ?: run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID")); return@webSocket
                }
                handler.handleConnection(chargePointId, this)
            }
        }
    }
}

class OcppMessageHandler(private val service: OcppService) {
    private val log = logger("io.konektis.ocpp.handler")
    private val json = service.json

    suspend fun handleConnection(chargePointId: String, session: DefaultWebSocketSession) {
        log.info("New OCPP connection from {cp}", chargePointId)
        try {
            service.registerSession(chargePointId, session)
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    log.debug("recv from {cp}: {msg}", chargePointId, text)
                    try {
                        processMessage(chargePointId, text)?.let { session.send(Frame.Text(it)) }
                    } catch (e: Exception) {
                        log.error("error handling message from {cp}: {err}", chargePointId, e.message)
                        val messageId = runCatching {
                            Json.parseToJsonElement(text).jsonArray[1].jsonPrimitive.content
                        }.getOrDefault("unknown")
                        session.send(Frame.Text(errorResponse(messageId, ErrorCode.InternalError, e.message ?: "error")))
                    }
                }
            }
        } catch (e: Exception) {
            log.error("connection error for {cp}: {err}", chargePointId, e.message)
        } finally {
            service.unregisterSession(chargePointId, session)
            log.info("OCPP connection closed for {cp}", chargePointId)
        }
    }

    private suspend fun processMessage(chargePointId: String, message: String): String? {
        val arr = Json.parseToJsonElement(message).jsonArray
        require(arr.size >= 3) { "Invalid OCPP message format" }
        val messageTypeId = arr[0].jsonPrimitive.int
        val uniqueId = arr[1].jsonPrimitive.content
        return when (messageTypeId) {
            MessageType.CALL.value -> {
                val action = arr[2].jsonPrimitive.content
                val payload = if (arr.size > 3) arr[3].jsonObject else JsonObject(emptyMap())
                handleCall(chargePointId, uniqueId, action, payload)
            }
            MessageType.CALL_RESULT.value -> {
                val payload = if (arr.size > 2) arr[2].jsonObject else JsonObject(emptyMap())
                service.completeCall(uniqueId, payload); null
            }
            MessageType.CALL_ERROR.value -> {
                val code = if (arr.size > 2) arr[2].jsonPrimitive.content else "Unknown"
                val desc = if (arr.size > 3) arr[3].jsonPrimitive.content else ""
                service.failCall(uniqueId, "$code: $desc"); null
            }
            else -> throw IllegalArgumentException("Unknown message type: $messageTypeId")
        }
    }

    private suspend fun handleCall(chargePointId: String, uniqueId: String, action: String, payload: JsonObject): String {
        val gated = action != Action.BootNotification.name && action != Action.Heartbeat.name
        if (gated && !service.isCallAllowed(chargePointId)) {
            return errorResponse(uniqueId, ErrorCode.SecurityError, "Charge point not accepted")
        }
        val responsePayload: JsonObject = when (action) {
            Action.BootNotification.name ->
                json.encodeToJsonElement(service.handleBootNotification(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.Heartbeat.name ->
                json.encodeToJsonElement(service.handleHeartbeat(chargePointId)).jsonObject
            Action.Authorize.name ->
                json.encodeToJsonElement(service.handleAuthorize(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StartTransaction.name ->
                json.encodeToJsonElement(service.handleStartTransaction(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StopTransaction.name ->
                json.encodeToJsonElement(service.handleStopTransaction(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StatusNotification.name ->
                json.encodeToJsonElement(service.handleStatusNotification(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.MeterValues.name ->
                json.encodeToJsonElement(service.handleMeterValues(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.DataTransfer.name ->
                json.encodeToJsonElement(service.handleDataTransfer(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            else -> return errorResponse(uniqueId, ErrorCode.NotSupported, "Action $action is not supported")
        }
        return buildJsonArray { add(MessageType.CALL_RESULT.value); add(uniqueId); add(responsePayload) }.toString()
    }

    private fun errorResponse(uniqueId: String, code: ErrorCode, desc: String): String =
        buildJsonArray { add(MessageType.CALL_ERROR.value); add(uniqueId); add(code.name); add(desc); add(JsonObject(emptyMap())) }.toString()
}
