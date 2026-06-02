package io.konektis.ocpp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * OCPP 1.6J Message Types
 */
enum class MessageType(val value: Int) {
    CALL(2),
    CALL_RESULT(3),
    CALL_ERROR(4)
}

/**
 * OCPP 1.6J Action Types
 */
enum class Action {
    // Core Profile
    Authorize,
    BootNotification,
    ChangeAvailability,
    ChangeConfiguration,
    ClearCache,
    DataTransfer,
    GetConfiguration,
    Heartbeat,
    MeterValues,
    RemoteStartTransaction,
    RemoteStopTransaction,
    Reset,
    StartTransaction,
    StatusNotification,
    StopTransaction,
    UnlockConnector,
    
    // Smart Charging Profile
    ClearChargingProfile,
    GetCompositeSchedule,
    SetChargingProfile,
    
    // Remote Trigger Profile
    TriggerMessage,
    
    // Firmware Management Profile
    GetDiagnostics,
    DiagnosticsStatusNotification,
    FirmwareStatusNotification,
    UpdateFirmware,
    
    // Local Auth List Management Profile
    GetLocalListVersion,
    SendLocalList,
    
    // Reservation Profile
    CancelReservation,
    ReserveNow
}

/**
 * OCPP Error Codes
 */
enum class ErrorCode {
    NotImplemented,
    NotSupported,
    InternalError,
    ProtocolError,
    SecurityError,
    FormationViolation,
    PropertyConstraintViolation,
    OccurenceConstraintViolation,
    TypeConstraintViolation,
    GenericError
}

/**
 * Base OCPP Message
 */
sealed class OcppMessage {
    abstract val messageTypeId: Int
    abstract val uniqueId: String
}

/**
 * OCPP Call Message (Request from Charge Point or Central System)
 */
@Serializable
data class OcppCall(
    override val messageTypeId: Int = MessageType.CALL.value,
    override val uniqueId: String,
    val action: String,
    val payload: JsonObject
) : OcppMessage()

/**
 * OCPP CallResult Message (Response)
 */
@Serializable
data class OcppCallResult(
    override val messageTypeId: Int = MessageType.CALL_RESULT.value,
    override val uniqueId: String,
    val payload: JsonObject
) : OcppMessage()

/**
 * OCPP CallError Message (Error Response)
 */
@Serializable
data class OcppCallError(
    override val messageTypeId: Int = MessageType.CALL_ERROR.value,
    override val uniqueId: String,
    val errorCode: String,
    val errorDescription: String,
    val errorDetails: JsonObject = JsonObject(emptyMap())
) : OcppMessage()

// ============================================================================
// Core Profile Messages
// ============================================================================

@Serializable
data class BootNotificationRequest(
    val chargePointVendor: String,
    val chargePointModel: String,
    val chargePointSerialNumber: String? = null,
    val chargeBoxSerialNumber: String? = null,
    val firmwareVersion: String? = null,
    val iccid: String? = null,
    val imsi: String? = null,
    val meterType: String? = null,
    val meterSerialNumber: String? = null
)

@Serializable
data class BootNotificationResponse(
    val status: RegistrationStatus,
    val currentTime: String,
    val interval: Int
)

enum class RegistrationStatus {
    Accepted,
    Pending,
    Rejected
}

@Serializable
data class HeartbeatRequest(
    val dummy: String? = null // Empty request
)

@Serializable
data class HeartbeatResponse(
    val currentTime: String
)

@Serializable
data class AuthorizeRequest(
    val idTag: String
)

@Serializable
data class AuthorizeResponse(
    val idTagInfo: IdTagInfo
)

@Serializable
data class IdTagInfo(
    val status: AuthorizationStatus,
    val expiryDate: String? = null,
    val parentIdTag: String? = null
)

enum class AuthorizationStatus {
    Accepted,
    Blocked,
    Expired,
    Invalid,
    ConcurrentTx
}

@Serializable
data class StartTransactionRequest(
    val connectorId: Int,
    val idTag: String,
    val meterStart: Int,
    val timestamp: String,
    val reservationId: Int? = null
)

@Serializable
data class StartTransactionResponse(
    val transactionId: Int,
    val idTagInfo: IdTagInfo
)

@Serializable
data class StopTransactionRequest(
    val transactionId: Int,
    val timestamp: String,
    val meterStop: Int,
    val idTag: String? = null,
    val reason: Reason? = null,
    val transactionData: List<MeterValue>? = null
)

@Serializable
data class StopTransactionResponse(
    val idTagInfo: IdTagInfo? = null
)

enum class Reason {
    EmergencyStop,
    EVDisconnected,
    HardReset,
    Local,
    Other,
    PowerLoss,
    Reboot,
    Remote,
    SoftReset,
    UnlockCommand,
    DeAuthorized
}

@Serializable
data class StatusNotificationRequest(
    val connectorId: Int,
    val errorCode: ChargePointErrorCode,
    val status: ChargePointStatus,
    val timestamp: String? = null,
    val info: String? = null,
    val vendorId: String? = null,
    val vendorErrorCode: String? = null
)

@Serializable
data class StatusNotificationResponse(
    val dummy: String? = null // Empty response
)

enum class ChargePointStatus {
    Available,
    Preparing,
    Charging,
    SuspendedEVSE,
    SuspendedEV,
    Finishing,
    Reserved,
    Unavailable,
    Faulted
}

enum class ChargePointErrorCode {
    ConnectorLockFailure,
    EVCommunicationError,
    GroundFailure,
    HighTemperature,
    InternalError,
    LocalListConflict,
    NoError,
    OtherError,
    OverCurrentFailure,
    PowerMeterFailure,
    PowerSwitchFailure,
    ReaderFailure,
    ResetFailure,
    UnderVoltage,
    OverVoltage,
    WeakSignal
}

@Serializable
data class MeterValuesRequest(
    val connectorId: Int,
    val transactionId: Int? = null,
    val meterValue: List<MeterValue>
)

@Serializable
data class MeterValuesResponse(
    val dummy: String? = null // Empty response
)

@Serializable
data class MeterValue(
    val timestamp: String,
    val sampledValue: List<SampledValue>
)

@Serializable
data class SampledValue(
    val value: String,
    val context: ReadingContext? = null,
    val format: ValueFormat? = null,
    val measurand: Measurand? = null,
    val phase: Phase? = null,
    val location: Location? = null,
    val unit: UnitOfMeasure? = null
)

@Serializable
enum class ReadingContext {
    @SerialName("Interruption.Begin") InterruptionBegin,
    @SerialName("Interruption.End") InterruptionEnd,
    @SerialName("Sample.Clock") SampleClock,
    @SerialName("Sample.Periodic") SamplePeriodic,
    @SerialName("Transaction.Begin") TransactionBegin,
    @SerialName("Transaction.End") TransactionEnd,
    Trigger,
    Other,
}

enum class ValueFormat {
    Raw,
    SignedData
}

/**
 * OCPP measurands. The wire form uses dotted names (e.g. "Power.Active.Import"), but a dot is an
 * illegal JVM field identifier, so the constants get legal names and carry the wire value as both a
 * @SerialName (for (de)serialisation) and a [wire] property (for matching in code via [wire]).
 */
@Serializable
enum class Measurand(val wire: String) {
    @SerialName("Energy.Active.Export.Register") EnergyActiveExportRegister("Energy.Active.Export.Register"),
    @SerialName("Energy.Active.Import.Register") EnergyActiveImportRegister("Energy.Active.Import.Register"),
    @SerialName("Energy.Reactive.Export.Register") EnergyReactiveExportRegister("Energy.Reactive.Export.Register"),
    @SerialName("Energy.Reactive.Import.Register") EnergyReactiveImportRegister("Energy.Reactive.Import.Register"),
    @SerialName("Energy.Active.Export.Interval") EnergyActiveExportInterval("Energy.Active.Export.Interval"),
    @SerialName("Energy.Active.Import.Interval") EnergyActiveImportInterval("Energy.Active.Import.Interval"),
    @SerialName("Energy.Reactive.Export.Interval") EnergyReactiveExportInterval("Energy.Reactive.Export.Interval"),
    @SerialName("Energy.Reactive.Import.Interval") EnergyReactiveImportInterval("Energy.Reactive.Import.Interval"),
    @SerialName("Power.Active.Export") PowerActiveExport("Power.Active.Export"),
    @SerialName("Power.Active.Import") PowerActiveImport("Power.Active.Import"),
    @SerialName("Power.Offered") PowerOffered("Power.Offered"),
    @SerialName("Power.Reactive.Export") PowerReactiveExport("Power.Reactive.Export"),
    @SerialName("Power.Reactive.Import") PowerReactiveImport("Power.Reactive.Import"),
    @SerialName("Power.Factor") PowerFactor("Power.Factor"),
    @SerialName("Current.Import") CurrentImport("Current.Import"),
    @SerialName("Current.Export") CurrentExport("Current.Export"),
    @SerialName("Current.Offered") CurrentOffered("Current.Offered"),
    @SerialName("Voltage") Voltage("Voltage"),
    @SerialName("Frequency") Frequency("Frequency"),
    @SerialName("Temperature") Temperature("Temperature"),
    @SerialName("SoC") SoC("SoC"),
    @SerialName("RPM") RPM("RPM"),
}

@Serializable
enum class Phase {
    L1,
    L2,
    L3,
    N,
    @SerialName("L1-N") L1N,
    @SerialName("L2-N") L2N,
    @SerialName("L3-N") L3N,
    @SerialName("L1-L2") L1L2,
    @SerialName("L2-L3") L2L3,
    @SerialName("L3-L1") L3L1,
}

enum class Location {
    Cable,
    EV,
    Inlet,
    Outlet,
    Body
}

enum class UnitOfMeasure {
    Wh,
    kWh,
    varh,
    kvarh,
    W,
    kW,
    VA,
    kVA,
    `var`,
    kvar,
    A,
    V,
    K,
    Celcius,
    Celsius,
    Fahrenheit,
    Percent
}

@Serializable
data class DataTransferRequest(
    val vendorId: String,
    val messageId: String? = null,
    val data: String? = null
)

@Serializable
data class DataTransferResponse(
    val status: DataTransferStatus,
    val data: String? = null
)

enum class DataTransferStatus {
    Accepted,
    Rejected,
    UnknownMessageId,
    UnknownVendorId
}

// ============================================================================
// Central System Initiated Messages
// ============================================================================

@Serializable
data class ChangeAvailabilityRequest(
    val connectorId: Int,
    val type: AvailabilityType
)

@Serializable
data class ChangeAvailabilityResponse(
    val status: AvailabilityStatus
)

enum class AvailabilityType {
    Inoperative,
    Operative
}

enum class AvailabilityStatus {
    Accepted,
    Rejected,
    Scheduled
}

@Serializable
data class ChangeConfigurationRequest(
    val key: String,
    val value: String
)

@Serializable
data class ChangeConfigurationResponse(
    val status: ConfigurationStatus
)

enum class ConfigurationStatus {
    Accepted,
    Rejected,
    RebootRequired,
    NotSupported
}

@Serializable
data class ClearCacheRequest(
    val dummy: String? = null
)

@Serializable
data class ClearCacheResponse(
    val status: ClearCacheStatus
)

enum class ClearCacheStatus {
    Accepted,
    Rejected
}

@Serializable
data class GetConfigurationRequest(
    val key: List<String>? = null
)

@Serializable
data class GetConfigurationResponse(
    val configurationKey: List<ConfigurationKey>? = null,
    val unknownKey: List<String>? = null
)

@Serializable
data class ConfigurationKey(
    val key: String,
    val readonly: Boolean,
    val value: String? = null
)

@Serializable
data class RemoteStartTransactionRequest(
    val connectorId: Int? = null,
    val idTag: String,
    val chargingProfile: ChargingProfile? = null
)

@Serializable
data class RemoteStartTransactionResponse(
    val status: RemoteStartStopStatus
)

@Serializable
data class RemoteStopTransactionRequest(
    val transactionId: Int
)

@Serializable
data class RemoteStopTransactionResponse(
    val status: RemoteStartStopStatus
)

enum class RemoteStartStopStatus {
    Accepted,
    Rejected
}

@Serializable
data class ResetRequest(
    val type: ResetType
)

@Serializable
data class ResetResponse(
    val status: ResetStatus
)

enum class ResetType {
    Hard,
    Soft
}

enum class ResetStatus {
    Accepted,
    Rejected
}

@Serializable
data class UnlockConnectorRequest(
    val connectorId: Int
)

@Serializable
data class UnlockConnectorResponse(
    val status: UnlockStatus
)

enum class UnlockStatus {
    Unlocked,
    UnlockFailed,
    NotSupported
}

// ============================================================================
// Smart Charging Profile
// ============================================================================

@Serializable
data class ChargingProfile(
    val chargingProfileId: Int,
    val transactionId: Int? = null,
    val stackLevel: Int,
    val chargingProfilePurpose: ChargingProfilePurposeType,
    val chargingProfileKind: ChargingProfileKindType,
    val recurrencyKind: RecurrencyKindType? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val chargingSchedule: ChargingSchedule
)

@Serializable
data class ChargingSchedule(
    val duration: Int? = null,
    val startSchedule: String? = null,
    val chargingRateUnit: ChargingRateUnitType,
    val chargingSchedulePeriod: List<ChargingSchedulePeriod>,
    val minChargingRate: Double? = null
)

@Serializable
data class ChargingSchedulePeriod(
    val startPeriod: Int,
    val limit: Double,
    val numberPhases: Int? = null
)

enum class ChargingProfilePurposeType {
    ChargePointMaxProfile,
    TxDefaultProfile,
    TxProfile
}

enum class ChargingProfileKindType {
    Absolute,
    Recurring,
    Relative
}

enum class RecurrencyKindType {
    Daily,
    Weekly
}

enum class ChargingRateUnitType {
    W,
    A
}

@Serializable
data class SetChargingProfileRequest(
    val connectorId: Int,
    val csChargingProfiles: ChargingProfile
)

@Serializable
data class SetChargingProfileResponse(
    val status: ChargingProfileStatus
)

enum class ChargingProfileStatus {
    Accepted,
    Rejected,
    NotSupported
}

@Serializable
data class ClearChargingProfileRequest(
    val id: Int? = null,
    val connectorId: Int? = null,
    val chargingProfilePurpose: ChargingProfilePurposeType? = null,
    val stackLevel: Int? = null
)

@Serializable
data class ClearChargingProfileResponse(
    val status: ClearChargingProfileStatus
)

enum class ClearChargingProfileStatus {
    Accepted,
    Unknown
}

@Serializable
data class GetCompositeScheduleRequest(
    val connectorId: Int,
    val duration: Int,
    val chargingRateUnit: ChargingRateUnitType? = null
)

@Serializable
data class GetCompositeScheduleResponse(
    val status: GetCompositeScheduleStatus,
    val connectorId: Int? = null,
    val scheduleStart: String? = null,
    val chargingSchedule: ChargingSchedule? = null
)

enum class GetCompositeScheduleStatus {
    Accepted,
    Rejected
}