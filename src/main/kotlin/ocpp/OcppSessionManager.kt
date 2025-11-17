package io.konektis.ocpp

import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a connected charge point session
 */
data class ChargePointSession(
    val chargePointId: String,
    val session: DefaultWebSocketSession,
    val connectedAt: Instant = Instant.now(),
    var lastHeartbeat: Instant = Instant.now(),
    var bootNotificationReceived: Boolean = false,
    var registrationStatus: RegistrationStatus = RegistrationStatus.Pending,
    val connectors: MutableMap<Int, ConnectorState> = ConcurrentHashMap(),
    val activeTransactions: MutableMap<Int, Transaction> = ConcurrentHashMap(),
    val configuration: MutableMap<String, String> = ConcurrentHashMap()
)

/**
 * Connector state information
 */
data class ConnectorState(
    val connectorId: Int,
    var status: ChargePointStatus = ChargePointStatus.Available,
    var errorCode: ChargePointErrorCode = ChargePointErrorCode.NoError,
    var lastStatusUpdate: Instant = Instant.now(),
    var currentTransactionId: Int? = null
)

/**
 * Transaction information
 */
data class Transaction(
    val transactionId: Int,
    val connectorId: Int,
    val idTag: String,
    val startTime: Instant,
    val meterStart: Int,
    var meterStop: Int? = null,
    var stopTime: Instant? = null,
    var stopReason: Reason? = null
)

/**
 * OCPP Session Manager - manages all charge point connections and state
 */
class OcppSessionManager {
    private val sessions = ConcurrentHashMap<String, ChargePointSession>()
    private val sessionMutex = Mutex()
    private val transactionIdCounter = AtomicInteger(1)
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Register a new charge point session
     */
    suspend fun registerSession(chargePointId: String, session: DefaultWebSocketSession) {
        sessionMutex.withLock {
            sessions[chargePointId] = ChargePointSession(
                chargePointId = chargePointId,
                session = session
            )
            println("[INFO] Registered charge point: $chargePointId")
        }
    }

    /**
     * Unregister a charge point session
     */
    suspend fun unregisterSession(chargePointId: String) {
        sessionMutex.withLock {
            sessions.remove(chargePointId)?.let {
                println("[INFO] Unregistered charge point: $chargePointId")
            }
        }
    }

    /**
     * Get a session by charge point ID
     */
    fun getSession(chargePointId: String): ChargePointSession? {
        return sessions[chargePointId]
    }

    /**
     * Get all active sessions
     */
    fun getAllSessions(): List<ChargePointSession> {
        return sessions.values.toList()
    }

    /**
     * Generate a new transaction ID
     */
    fun generateTransactionId(): Int {
        return transactionIdCounter.getAndIncrement()
    }

    /**
     * Handle BootNotification request
     */
    fun handleBootNotification(
        chargePointId: String,
        request: BootNotificationRequest
    ): BootNotificationResponse {
        val session = getSession(chargePointId)
        
        println("[INFO] BootNotification from $chargePointId: vendor=${request.chargePointVendor}, model=${request.chargePointModel}, serial=${request.chargePointSerialNumber}")

        // Accept the charge point
        val status = RegistrationStatus.Accepted
        
        session?.let {
            it.bootNotificationReceived = true
            it.registrationStatus = status
        }

        return BootNotificationResponse(
            status = status,
            currentTime = getCurrentTimestamp(),
            interval = 300 // Heartbeat interval in seconds
        )
    }

    /**
     * Handle Heartbeat request
     */
    fun handleHeartbeat(chargePointId: String): HeartbeatResponse {
        val session = getSession(chargePointId)
        session?.lastHeartbeat = Instant.now()
        
        println("[DEBUG] Heartbeat from $chargePointId")
        
        return HeartbeatResponse(
            currentTime = getCurrentTimestamp()
        )
    }

    /**
     * Handle Authorize request
     */
    fun handleAuthorize(
        chargePointId: String,
        request: AuthorizeRequest
    ): AuthorizeResponse {
        println("[INFO] Authorize request from $chargePointId for idTag: ${request.idTag}")
        
        // Simple authorization logic - accept all tags for now
        // In production, this should check against a database or authorization service
        val status = if (request.idTag.isNotBlank()) {
            AuthorizationStatus.Accepted
        } else {
            AuthorizationStatus.Invalid
        }

        return AuthorizeResponse(
            idTagInfo = IdTagInfo(
                status = status,
                expiryDate = null,
                parentIdTag = null
            )
        )
    }

    /**
     * Handle StartTransaction request
     */
    fun handleStartTransaction(
        chargePointId: String,
        request: StartTransactionRequest
    ): StartTransactionResponse {
        val session = getSession(chargePointId)
        val transactionId = generateTransactionId()
        
        println("[INFO] StartTransaction from $chargePointId: connector=${request.connectorId}, idTag=${request.idTag}, transactionId=$transactionId")

        // Create transaction record
        val transaction = Transaction(
            transactionId = transactionId,
            connectorId = request.connectorId,
            idTag = request.idTag,
            startTime = Instant.now(),
            meterStart = request.meterStart
        )

        session?.let {
            it.activeTransactions[transactionId] = transaction
            it.connectors.getOrPut(request.connectorId) {
                ConnectorState(request.connectorId)
            }.apply {
                currentTransactionId = transactionId
                status = ChargePointStatus.Charging
            }
        }

        return StartTransactionResponse(
            transactionId = transactionId,
            idTagInfo = IdTagInfo(
                status = AuthorizationStatus.Accepted
            )
        )
    }

    /**
     * Handle StopTransaction request
     */
    fun handleStopTransaction(
        chargePointId: String,
        request: StopTransactionRequest
    ): StopTransactionResponse {
        val session = getSession(chargePointId)
        
        println("[INFO] StopTransaction from $chargePointId: transactionId=${request.transactionId}, meterStop=${request.meterStop}, reason=${request.reason}")

        session?.let {
            it.activeTransactions[request.transactionId]?.let { transaction ->
                transaction.meterStop = request.meterStop
                transaction.stopTime = Instant.now()
                transaction.stopReason = request.reason
                
                // Update connector state
                it.connectors[transaction.connectorId]?.apply {
                    currentTransactionId = null
                    status = ChargePointStatus.Available
                }
                
                // Remove from active transactions
                it.activeTransactions.remove(request.transactionId)
            }
        }

        return StopTransactionResponse(
            idTagInfo = IdTagInfo(
                status = AuthorizationStatus.Accepted
            )
        )
    }

    /**
     * Handle StatusNotification request
     */
    fun handleStatusNotification(
        chargePointId: String,
        request: StatusNotificationRequest
    ): StatusNotificationResponse {
        val session = getSession(chargePointId)
        
        println("[INFO] StatusNotification from $chargePointId: connector=${request.connectorId}, status=${request.status}, errorCode=${request.errorCode}")

        session?.let {
            it.connectors.getOrPut(request.connectorId) {
                ConnectorState(request.connectorId)
            }.apply {
                status = request.status
                errorCode = request.errorCode
                lastStatusUpdate = Instant.now()
            }
        }

        return StatusNotificationResponse()
    }

    /**
     * Handle MeterValues request
     */
    fun handleMeterValues(
        chargePointId: String,
        request: MeterValuesRequest
    ): MeterValuesResponse {
        println("[DEBUG] MeterValues from $chargePointId: connector=${request.connectorId}, transactionId=${request.transactionId}, values=${request.meterValue.size}")

        // In production, store these meter values in a database
        return MeterValuesResponse()
    }

    /**
     * Handle DataTransfer request
     */
    fun handleDataTransfer(
        chargePointId: String,
        request: DataTransferRequest
    ): DataTransferResponse {
        println("[INFO] DataTransfer from $chargePointId: vendorId=${request.vendorId}, messageId=${request.messageId}")

        // Handle vendor-specific data transfer
        return DataTransferResponse(
            status = DataTransferStatus.Accepted,
            data = null
        )
    }

    /**
     * Send a remote command to a charge point
     */
    suspend fun sendRemoteStartTransaction(
        chargePointId: String,
        request: RemoteStartTransactionRequest
    ): RemoteStartTransactionResponse? {
        val session = getSession(chargePointId) ?: return null
        
        val uniqueId = generateUniqueId()
        val call = OcppCall(
            uniqueId = uniqueId,
            action = Action.RemoteStartTransaction.name,
            payload = json.encodeToJsonElement(request).jsonObject
        )

        try {
            session.session.send(Frame.Text(serializeOcppMessage(call)))
            println("[INFO] Sent RemoteStartTransaction to $chargePointId")
            // In production, wait for response with timeout
            return RemoteStartTransactionResponse(status = RemoteStartStopStatus.Accepted)
        } catch (e: Exception) {
            println("[ERROR] Failed to send RemoteStartTransaction to $chargePointId: ${e.message}")
            return null
        }
    }

    /**
     * Send a remote stop transaction command
     */
    suspend fun sendRemoteStopTransaction(
        chargePointId: String,
        request: RemoteStopTransactionRequest
    ): RemoteStopTransactionResponse? {
        val session = getSession(chargePointId) ?: return null
        
        val uniqueId = generateUniqueId()
        val call = OcppCall(
            uniqueId = uniqueId,
            action = Action.RemoteStopTransaction.name,
            payload = json.encodeToJsonElement(request).jsonObject
        )

        try {
            session.session.send(Frame.Text(serializeOcppMessage(call)))
            println("[INFO] Sent RemoteStopTransaction to $chargePointId")
            return RemoteStopTransactionResponse(status = RemoteStartStopStatus.Accepted)
        } catch (e: Exception) {
            println("[ERROR] Failed to send RemoteStopTransaction to $chargePointId: ${e.message}")
            return null
        }
    }

    /**
     * Send a reset command
     */
    suspend fun sendReset(
        chargePointId: String,
        request: ResetRequest
    ): ResetResponse? {
        val session = getSession(chargePointId) ?: return null
        
        val uniqueId = generateUniqueId()
        val call = OcppCall(
            uniqueId = uniqueId,
            action = Action.Reset.name,
            payload = json.encodeToJsonElement(request).jsonObject
        )

        try {
            session.session.send(Frame.Text(serializeOcppMessage(call)))
            println("[INFO] Sent Reset to $chargePointId")
            return ResetResponse(status = ResetStatus.Accepted)
        } catch (e: Exception) {
            println("[ERROR] Failed to send Reset to $chargePointId: ${e.message}")
            return null
        }
    }

    /**
     * Serialize OCPP message to JSON array format
     */
    private fun serializeOcppMessage(message: OcppMessage): String {
        return when (message) {
            is OcppCall -> buildJsonArray {
                add(message.messageTypeId)
                add(message.uniqueId)
                add(message.action)
                add(message.payload)
            }.toString()
            
            is OcppCallResult -> buildJsonArray {
                add(message.messageTypeId)
                add(message.uniqueId)
                add(message.payload)
            }.toString()
            
            is OcppCallError -> buildJsonArray {
                add(message.messageTypeId)
                add(message.uniqueId)
                add(message.errorCode)
                add(message.errorDescription)
                add(message.errorDetails)
            }.toString()
        }
    }

    /**
     * Get current timestamp in ISO 8601 format
     */
    private fun getCurrentTimestamp(): String {
        return Instant.now()
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /**
     * Generate a unique message ID
     */
    private fun generateUniqueId(): String {
        return java.util.UUID.randomUUID().toString()
    }
}