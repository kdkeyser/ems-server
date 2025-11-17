package io.konektis.ocpp

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

/**
 * OCPP 1.6J Server Configuration
 */
fun Application.configureOcppServer() {
    val sessionManager = OcppSessionManager()
    val messageHandler = OcppMessageHandler(sessionManager)
    
  /*  install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }*/
    
    routing {
        // OCPP WebSocket endpoint with charge point ID in path
        // Format: /ocpp/{chargePointId}
        webSocket("/ocpp/{chargePointId}") {
            val chargePointId = call.parameters["chargePointId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID"))
                return@webSocket
            }
            
            messageHandler.handleConnection(chargePointId, this)
        }
        
        // Alternative endpoint format: /ocpp/1.6/{chargePointId}
        webSocket("/ocpp/1.6/{chargePointId}") {
            val chargePointId = call.parameters["chargePointId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID"))
                return@webSocket
            }
            
            messageHandler.handleConnection(chargePointId, this)
        }
    }
}

/**
 * OCPP Message Handler - processes incoming OCPP messages
 */
class OcppMessageHandler(
    private val sessionManager: OcppSessionManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Handle a new WebSocket connection from a charge point
     */
    suspend fun handleConnection(chargePointId: String, session: DefaultWebSocketSession) {
        println("[INFO] New OCPP connection from charge point: $chargePointId")
        
        try {
            // Register the session
            sessionManager.registerSession(chargePointId, session)
            
            // Process incoming messages
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    println("[DEBUG] Received from $chargePointId: $text")
                    
                    try {
                        val response = processMessage(chargePointId, text)
                        if (response != null) {
                            session.send(Frame.Text(response))
                            println("[DEBUG] Sent to $chargePointId: $response")
                        }
                    } catch (e: Exception) {
                        println("[ERROR] Error processing message from $chargePointId: $text - ${e.message}")
                        
                        // Try to extract message ID for error response
                        val messageId = try {
                            val jsonArray = Json.parseToJsonElement(text).jsonArray
                            if (jsonArray.size >= 2) jsonArray[1].jsonPrimitive.content else "unknown"
                        } catch (ex: Exception) {
                            "unknown"
                        }
                        
                        val errorResponse = createErrorResponse(
                            messageId,
                            ErrorCode.InternalError,
                            e.message ?: "Unknown error"
                        )
                        session.send(Frame.Text(errorResponse))
                    }
                }
            }
        } catch (e: Exception) {
            println("[ERROR] Connection error for charge point: $chargePointId - ${e.message}")
        } finally {
            // Unregister the session when connection closes
            sessionManager.unregisterSession(chargePointId)
            println("[INFO] OCPP connection closed for charge point: $chargePointId")
        }
    }

    /**
     * Process an incoming OCPP message
     */
    private fun processMessage(chargePointId: String, message: String): String? {
        // Parse the JSON array format: [MessageTypeId, UniqueId, Action, Payload]
        val jsonArray = Json.parseToJsonElement(message).jsonArray
        
        if (jsonArray.size < 3) {
            throw IllegalArgumentException("Invalid OCPP message format")
        }
        
        val messageTypeId = jsonArray[0].jsonPrimitive.int
        val uniqueId = jsonArray[1].jsonPrimitive.content
        
        return when (messageTypeId) {
            MessageType.CALL.value -> {
                // This is a request from the charge point
                val action = jsonArray[2].jsonPrimitive.content
                val payload = if (jsonArray.size > 3) jsonArray[3].jsonObject else JsonObject(emptyMap())
                
                handleCall(chargePointId, uniqueId, action, payload)
            }
            
            MessageType.CALL_RESULT.value -> {
                // This is a response to our request
                val payload = if (jsonArray.size > 2) jsonArray[2].jsonObject else JsonObject(emptyMap())
                handleCallResult(chargePointId, uniqueId, payload)
                null // No response needed
            }
            
            MessageType.CALL_ERROR.value -> {
                // This is an error response to our request
                val errorCode = if (jsonArray.size > 2) jsonArray[2].jsonPrimitive.content else "Unknown"
                val errorDescription = if (jsonArray.size > 3) jsonArray[3].jsonPrimitive.content else ""
                handleCallError(chargePointId, uniqueId, errorCode, errorDescription)
                null // No response needed
            }
            
            else -> {
                throw IllegalArgumentException("Unknown message type: $messageTypeId")
            }
        }
    }

    /**
     * Handle a CALL message (request from charge point)
     */
    private fun handleCall(
        chargePointId: String,
        uniqueId: String,
        action: String,
        payload: JsonObject
    ): String {
        println("[INFO] Processing $action from $chargePointId")
        
        val responsePayload = when (action) {
            Action.BootNotification.name -> {
                val request = json.decodeFromJsonElement<BootNotificationRequest>(payload)
                val response = sessionManager.handleBootNotification(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.Heartbeat.name -> {
                val response = sessionManager.handleHeartbeat(chargePointId)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.Authorize.name -> {
                val request = json.decodeFromJsonElement<AuthorizeRequest>(payload)
                val response = sessionManager.handleAuthorize(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.StartTransaction.name -> {
                val request = json.decodeFromJsonElement<StartTransactionRequest>(payload)
                val response = sessionManager.handleStartTransaction(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.StopTransaction.name -> {
                val request = json.decodeFromJsonElement<StopTransactionRequest>(payload)
                val response = sessionManager.handleStopTransaction(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.StatusNotification.name -> {
                val request = json.decodeFromJsonElement<StatusNotificationRequest>(payload)
                val response = sessionManager.handleStatusNotification(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.MeterValues.name -> {
                val request = json.decodeFromJsonElement<MeterValuesRequest>(payload)
                val response = sessionManager.handleMeterValues(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            Action.DataTransfer.name -> {
                val request = json.decodeFromJsonElement<DataTransferRequest>(payload)
                val response = sessionManager.handleDataTransfer(chargePointId, request)
                json.encodeToJsonElement(response).jsonObject
            }
            
            else -> {
                println("[WARN] Unsupported action: $action from $chargePointId")
                return createErrorResponse(
                    uniqueId,
                    ErrorCode.NotSupported,
                    "Action $action is not supported"
                )
            }
        }
        
        return createCallResult(uniqueId, responsePayload)
    }

    /**
     * Handle a CALL_RESULT message (response from charge point)
     */
    private fun handleCallResult(
        chargePointId: String,
        uniqueId: String,
        payload: JsonObject
    ) {
        println("[INFO] Received CallResult from $chargePointId for message $uniqueId")
        // In production, match this with pending requests and resolve promises
    }

    /**
     * Handle a CALL_ERROR message (error response from charge point)
     */
    private fun handleCallError(
        chargePointId: String,
        uniqueId: String,
        errorCode: String,
        errorDescription: String
    ) {
        println("[ERROR] Received CallError from $chargePointId for message $uniqueId: $errorCode - $errorDescription")
        // In production, match this with pending requests and reject promises
    }

    /**
     * Create a CALL_RESULT response
     */
    private fun createCallResult(uniqueId: String, payload: JsonObject): String {
        return buildJsonArray {
            add(MessageType.CALL_RESULT.value)
            add(uniqueId)
            add(payload)
        }.toString()
    }

    /**
     * Create a CALL_ERROR response
     */
    private fun createErrorResponse(
        uniqueId: String,
        errorCode: ErrorCode,
        errorDescription: String
    ): String {
        return buildJsonArray {
            add(MessageType.CALL_ERROR.value)
            add(uniqueId)
            add(errorCode.name)
            add(errorDescription)
            add(JsonObject(emptyMap()))
        }.toString()
    }
}