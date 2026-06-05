package io.konektis.ocpp

import io.klogging.Klogging
import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// ---- Live (in-memory) state ----

class ConnectorState(
    val connectorId: Int,
    var status: ChargePointStatus = ChargePointStatus.Available,
    var errorCode: ChargePointErrorCode = ChargePointErrorCode.NoError,
    var currentTransactionId: Int? = null,
    var lastPowerW: Int? = null,
)

class ChargePointSession(
    val chargePointId: String,
    val session: DefaultWebSocketSession,
    var vendor: String? = null,
    var model: String? = null,
    var smartChargingSupported: Boolean = false,
    var powerImportSeen: Boolean = false,
    var registrationStatus: RegistrationStatus = RegistrationStatus.Pending,
    val connectors: MutableMap<Int, ConnectorState> = ConcurrentHashMap(),
    val activeTransactions: MutableMap<Int, ActiveTransaction> = ConcurrentHashMap(),
)

data class ActiveTransaction(
    val transactionId: Int, val connectorId: Int, val idTag: String, val startTime: Instant, val meterStart: Int,
)

// ---- Serializable view models pushed to the webpage ----

@Serializable
data class OcppState(val chargePoints: List<OcppChargePointView>)

@Serializable
data class OcppChargePointView(
    val chargePointId: String,
    val online: Boolean,
    val vendor: String?,
    val model: String?,
    val smartChargingSupported: Boolean,
    val powerReadable: Boolean,
    val connectors: List<OcppConnectorView>,
)

@Serializable
data class OcppConnectorView(val connectorId: Int, val status: String, val powerW: Int?, val transactionId: Int?)

class OcppService(
    private val chargePoints: ChargePointStore,
    private val idTags: IdTagStore,
    private val transactions: TransactionStore,
    private val config: OcppConfig,
) : Klogging {

    private val sessions = ConcurrentHashMap<String, ChargePointSession>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val transactionIdCounter = AtomicInteger(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _stateFlow = MutableStateFlow(OcppState(emptyList()))
    val stateFlow: StateFlow<OcppState> = _stateFlow.asStateFlow()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun initStores() {
        chargePoints.init(); idTags.init(); transactions.init()
        transactionIdCounter.set(transactions.maxTransactionId() + 1)
    }

    fun getSession(id: String): ChargePointSession? = sessions[id]

    suspend fun registerSession(chargePointId: String, session: DefaultWebSocketSession) {
        sessions[chargePointId] = ChargePointSession(chargePointId, session)
        logger.info("Registered charge point $chargePointId")
        recomputeState()
    }

    suspend fun unregisterSession(chargePointId: String) {
        sessions.remove(chargePointId)
        logger.info("Unregistered charge point $chargePointId")
        recomputeState()
    }

    suspend fun handleBootNotification(chargePointId: String, request: BootNotificationRequest): BootNotificationResponse {
        logger.info("BootNotification $chargePointId vendor=${request.chargePointVendor} model=${request.chargePointModel}")
        chargePoints.recordBoot(chargePointId, request.chargePointVendor, request.chargePointModel, request.firmwareVersion)
        val existing = chargePoints.get(chargePointId)
        val accepted = when {
            existing?.accepted == true -> true
            config.acceptUnknownChargePoints -> { chargePoints.setAccepted(chargePointId, true); true }
            else -> false
        }
        val status = if (accepted) RegistrationStatus.Accepted else RegistrationStatus.Pending
        sessions[chargePointId]?.apply {
            vendor = request.chargePointVendor
            model = request.chargePointModel
            registrationStatus = status
            smartChargingSupported = existing?.smartChargingSupported ?: false
            powerImportSeen = existing?.powerImportSeen ?: false
        }
        recomputeState()
        if (status == RegistrationStatus.Accepted && config.autoProbeOnBoot) probeCapabilities(chargePointId)
        return BootNotificationResponse(status, currentTimestamp(), config.heartbeatInterval)
    }

    fun handleHeartbeat(chargePointId: String): HeartbeatResponse = HeartbeatResponse(currentTimestamp())

    suspend fun handleAuthorize(chargePointId: String, request: AuthorizeRequest): AuthorizeResponse =
        AuthorizeResponse(IdTagInfo(status = authorizeTag(request.idTag)))

    private suspend fun authorizeTag(idTag: String): AuthorizationStatus {
        if (idTag.isBlank()) return AuthorizationStatus.Invalid
        val record = idTags.get(idTag)
        return when {
            record != null -> runCatching { AuthorizationStatus.valueOf(record.status) }.getOrDefault(AuthorizationStatus.Invalid)
            config.acceptUnknownIdTags -> AuthorizationStatus.Accepted
            else -> AuthorizationStatus.Invalid
        }
    }

    suspend fun handleStartTransaction(chargePointId: String, request: StartTransactionRequest): StartTransactionResponse {
        val transactionId = transactionIdCounter.getAndIncrement()
        sessions[chargePointId]?.let { s ->
            s.activeTransactions[transactionId] =
                ActiveTransaction(transactionId, request.connectorId, request.idTag, Instant.now(), request.meterStart)
            s.connectors.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }.apply {
                currentTransactionId = transactionId
                status = ChargePointStatus.Charging
            }
        }
        recomputeState()
        return StartTransactionResponse(transactionId, IdTagInfo(status = authorizeTag(request.idTag)))
    }

    suspend fun handleStopTransaction(chargePointId: String, request: StopTransactionRequest): StopTransactionResponse {
        val s = sessions[chargePointId]
        val tx = s?.activeTransactions?.remove(request.transactionId)
        if (tx != null) {
            s.connectors[tx.connectorId]?.apply { currentTransactionId = null; status = ChargePointStatus.Available }
            transactions.record(
                transactionId = tx.transactionId, chargePointId = chargePointId, connectorId = tx.connectorId,
                idTag = tx.idTag, meterStart = tx.meterStart, meterStop = request.meterStop,
                startTime = tx.startTime.toEpochMilli(), stopTime = Instant.now().toEpochMilli(),
                stopReason = request.reason?.name,
            )
        }
        recomputeState()
        return StopTransactionResponse(IdTagInfo(status = AuthorizationStatus.Accepted))
    }

    suspend fun handleStatusNotification(chargePointId: String, request: StatusNotificationRequest): StatusNotificationResponse {
        sessions[chargePointId]?.connectors?.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }?.apply {
            status = request.status; errorCode = request.errorCode
        }
        recomputeState()
        return StatusNotificationResponse()
    }

    suspend fun handleMeterValues(chargePointId: String, request: MeterValuesRequest): MeterValuesResponse {
        val powerW = extractActivePowerW(request)
        if (powerW != null) {
            val s = sessions[chargePointId]
            s?.connectors?.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }?.lastPowerW = powerW
            if (s != null && !s.powerImportSeen) {
                s.powerImportSeen = true
                chargePoints.setPowerImportSeen(chargePointId, true)
            }
            recomputeState()
        }
        return MeterValuesResponse()
    }

    /** Pull Power.Active.Import (W) from the sampled values, if present. */
    private fun extractActivePowerW(request: MeterValuesRequest): Int? {
        for (mv in request.meterValue) for (sv in mv.sampledValue) {
            if (sv.measurand == Measurand.PowerActiveImport) {
                val v = sv.value.toDoubleOrNull() ?: continue
                val watts = if (sv.unit == UnitOfMeasure.kW) v * 1000 else v
                return watts.toInt()
            }
        }
        return null
    }

    suspend fun handleDataTransfer(chargePointId: String, request: DataTransferRequest): DataTransferResponse =
        DataTransferResponse(status = DataTransferStatus.Accepted, data = null)

    suspend fun recentTransactions(limit: Int): List<TransactionRecord> = transactions.recent(limit)

    /** Latest active-power reading (W) for a connector, or null until one arrives. */
    fun latestPowerW(chargePointId: String, connectorId: Int): Int? =
        sessions[chargePointId]?.connectors?.get(connectorId)?.lastPowerW

    /** Latest known OCPP connector status, or null if the charge point/connector is unknown. */
    fun connectorStatus(chargePointId: String, connectorId: Int): ChargePointStatus? =
        sessions[chargePointId]?.connectors?.get(connectorId)?.status

    /** Open transaction id on a connector, or null when no transaction is running. */
    fun activeTransactionId(chargePointId: String, connectorId: Int): Int? =
        sessions[chargePointId]?.connectors?.get(connectorId)?.currentTransactionId

    /** True when the charge point is connected and advertised SmartCharging support. */
    fun isPowerControlCapable(chargePointId: String): Boolean =
        sessions[chargePointId]?.smartChargingSupported == true

    suspend fun listChargePoints() = chargePoints.all()
    suspend fun setChargePointAccepted(id: String, accepted: Boolean) = chargePoints.setAccepted(id, accepted)
    suspend fun listIdTags() = idTags.all()
    suspend fun putIdTag(idTag: String, status: String) = idTags.put(idTag, status)
    suspend fun deleteIdTag(idTag: String) = idTags.delete(idTag)

    /** Update SmartCharging support from a GetConfiguration reply (SupportedFeatureProfiles). */
    suspend fun applyCapabilityProbe(chargePointId: String, response: GetConfigurationResponse) {
        val profiles = response.configurationKey
            ?.firstOrNull { it.key == "SupportedFeatureProfiles" }?.value ?: ""
        val smartCharging = profiles.contains("SmartCharging", ignoreCase = true)
        val s = sessions[chargePointId]
        s?.smartChargingSupported = smartCharging
        chargePoints.setSmartChargingSupported(chargePointId, smartCharging)
        recomputeState()
    }

    /** Probe SmartCharging support in the background after boot. */
    fun probeCapabilities(chargePointId: String) {
        scope.launch {
            val resp = getConfiguration(chargePointId, listOf("SupportedFeatureProfiles"))
            if (resp != null) applyCapabilityProbe(chargePointId, resp)
        }
    }

    private fun recomputeState() {
        _stateFlow.value = OcppState(
            sessions.values.map { s ->
                OcppChargePointView(
                    chargePointId = s.chargePointId,
                    online = true,
                    vendor = s.vendor,
                    model = s.model,
                    smartChargingSupported = s.smartChargingSupported,
                    powerReadable = s.powerImportSeen,
                    connectors = s.connectors.values.map { c ->
                        OcppConnectorView(c.connectorId, c.status.name, c.lastPowerW, c.currentTransactionId)
                    }.sortedBy { it.connectorId },
                )
            }.sortedBy { it.chargePointId },
        )
    }

    /** Send a CALL to a charge point and await its CALL_RESULT payload, or null on timeout/error/unknown. */
    suspend fun sendCall(chargePointId: String, action: Action, payload: JsonObject): JsonObject? {
        val session = sessions[chargePointId] ?: run {
            logger.warn("sendCall: no session for {cp}", chargePointId); return null
        }
        val uniqueId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[uniqueId] = deferred
        val frame = buildJsonArray {
            add(MessageType.CALL.value); add(uniqueId); add(action.name); add(payload)
        }.toString()
        return try {
            session.session.send(Frame.Text(frame))
            logger.info("Sent {action} to {cp}", action.name, chargePointId)
            withTimeoutOrNull(config.callTimeoutSeconds.seconds) { deferred.await() }
                ?: run { logger.warn("{action} to {cp} timed out", action.name, chargePointId); null }
        } catch (e: Exception) {
            logger.error("sendCall {action} to {cp} failed: {err}", action.name, chargePointId, e.message)
            null
        } finally {
            pending.remove(uniqueId)
        }
    }

    fun completeCall(uniqueId: String, payload: JsonObject) {
        pending[uniqueId]?.complete(payload)
    }

    fun failCall(uniqueId: String, reason: String) {
        pending[uniqueId]?.completeExceptionally(RuntimeException(reason))
    }

    // ---- Outbound command helpers ----

    private fun JsonObject?.isAccepted(): Boolean =
        this?.get("status")?.jsonPrimitive?.content == "Accepted"

    /** Apply a charging limit to a connector. limit is in the given unit (A or W). Returns true if Accepted. */
    suspend fun setChargingProfile(chargePointId: String, connectorId: Int, limit: Double, unit: ChargingRateUnitType): Boolean {
        val profile = ChargingProfile(
            chargingProfileId = 1,
            stackLevel = 0,
            chargingProfilePurpose = ChargingProfilePurposeType.TxDefaultProfile,
            chargingProfileKind = ChargingProfileKindType.Absolute,
            chargingSchedule = ChargingSchedule(
                chargingRateUnit = unit,
                chargingSchedulePeriod = listOf(ChargingSchedulePeriod(startPeriod = 0, limit = limit)),
            ),
        )
        val payload = json.encodeToJsonElement(SetChargingProfileRequest(connectorId, profile)).jsonObject
        return sendCall(chargePointId, Action.SetChargingProfile, payload).isAccepted()
    }

    /**
     * Clear charging profiles on a connector (or all of them when [connectorId] is null). Used to
     * unstick a charger left at a 0 A limit. Returns true if the charge point replied Accepted.
     */
    suspend fun clearChargingProfile(chargePointId: String, connectorId: Int? = null): Boolean {
        val payload = buildJsonObject {
            if (connectorId != null) put("connectorId", connectorId)
        }
        return sendCall(chargePointId, Action.ClearChargingProfile, payload).isAccepted()
    }

    suspend fun getConfiguration(chargePointId: String, keys: List<String>? = null): GetConfigurationResponse? {
        val payload = json.encodeToJsonElement(GetConfigurationRequest(keys)).jsonObject
        val reply = sendCall(chargePointId, Action.GetConfiguration, payload) ?: return null
        return runCatching { json.decodeFromJsonElement<GetConfigurationResponse>(reply) }.getOrNull()
    }

    suspend fun remoteStart(chargePointId: String, idTag: String, connectorId: Int? = null): Boolean {
        val payload = json.encodeToJsonElement(RemoteStartTransactionRequest(idTag = idTag, connectorId = connectorId)).jsonObject
        return sendCall(chargePointId, Action.RemoteStartTransaction, payload).isAccepted()
    }

    suspend fun remoteStop(chargePointId: String, transactionId: Int): Boolean {
        val payload = json.encodeToJsonElement(RemoteStopTransactionRequest(transactionId)).jsonObject
        return sendCall(chargePointId, Action.RemoteStopTransaction, payload).isAccepted()
    }

    suspend fun reset(chargePointId: String, type: ResetType): Boolean {
        val payload = json.encodeToJsonElement(ResetRequest(type)).jsonObject
        return sendCall(chargePointId, Action.Reset, payload).isAccepted()
    }

    suspend fun triggerMessage(chargePointId: String, requestedMessage: String, connectorId: Int? = null): Boolean {
        val payload = buildJsonObject {
            put("requestedMessage", requestedMessage)
            if (connectorId != null) put("connectorId", connectorId)
        }
        return sendCall(chargePointId, Action.TriggerMessage, payload).isAccepted()
    }

    private fun currentTimestamp(): String =
        Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
