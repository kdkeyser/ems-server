package io.konektis.devices.charger

import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.ocpp.ChargePointStatus
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService

/** Maps an OCPP connector status to the app-facing ChargerConnection. */
internal fun chargerConnectionFrom(status: ChargePointStatus?): ChargerConnection = when (status) {
    ChargePointStatus.Available,
    ChargePointStatus.Reserved,
    ChargePointStatus.Unavailable -> ChargerConnection.NotConnected
    ChargePointStatus.Preparing,
    ChargePointStatus.SuspendedEV,
    ChargePointStatus.SuspendedEVSE,
    ChargePointStatus.Finishing -> ChargerConnection.Connected
    ChargePointStatus.Charging -> ChargerConnection.Charging
    ChargePointStatus.Faulted, null -> ChargerConnection.Unknown
}

/**
 * A car charger reached over OCPP 1.6J. Power is read from MeterValues (pushed by the charger)
 * and throttled via SetChargingProfile. When the charge point does not support SmartCharging,
 * setMaxChargerPower is a logged no-op (power is then handled elsewhere, e.g. a Webasto entry).
 *
 * SetChargingProfile only *limits* a transaction — it never starts one — so a car only draws power
 * once a transaction is open. This charger therefore opens/closes the OCPP transaction to match the
 * EMS intent encoded in the commanded current: a positive setpoint means "should be charging" and a
 * zero setpoint means "should not" (the surplus strategy holds an active solar session at its minimum
 * current, never 0, so 0 A reliably means the session is off). [idTag] authorises the EMS-started
 * transaction; it must be accepted by the charge point (allow-list or acceptUnknownIdTags).
 */
class OcppCharger(
    private val chargePointId: String,
    private val connectorId: Int,
    private val service: OcppService,
    private val idTag: String = "EMS",
) : Charger, Klogging {

    override suspend fun update() {
        // No polling: the charger pushes MeterValues. update() is a no-op so getState() stays cheap.
    }

    override suspend fun getState(): DeviceUpdate<ChargerState>? {
        val powerW = service.latestPowerW(chargePointId, connectorId)
        val connection = chargerConnectionFrom(service.connectorStatus(chargePointId, connectorId))
        // Return a state when we know either the power or the connection; only bail when both are absent
        // (so a connected-but-idle car still surfaces before any MeterValue arrives).
        if (powerW == null && connection == ChargerConnection.Unknown) return null
        return DeviceUpdate(
            GlobalTimeSource.source.markNow(),
            ChargerState(Watt(powerW ?: 0), connection)
        )
    }

    override suspend fun setMaxChargerPower(power: Watt) {
        // Send in amps (230V convention). The EMS has already clamped to the config max / fixed level.
        val amps = power.value / 230

        // Open or close the transaction to match intent BEFORE (and regardless of) SmartCharging:
        // without a transaction the car never charges, no matter what profile we set.
        if (amps == 0) {
            // Charging off: stopping the transaction is the off switch. A 0 A profile would just be
            // redundant chatter on every tick (and leaves a 0 A default behind), so skip it.
            ensureTransactionStopped()
            return
        }
        ensureTransactionStarted()

        if (!service.isPowerControlCapable(chargePointId)) {
            logger.debug { "OcppCharger $chargePointId: SmartCharging unsupported, skipping setChargingProfile" }
            return
        }
        val ok = service.setChargingProfile(chargePointId, connectorId, amps.toDouble(), ChargingRateUnitType.A)
        if (!ok) logger.warn { "OcppCharger $chargePointId: SetChargingProfile($amps A) not accepted" }
    }

    /** Start a transaction when a car is plugged in but none is open yet. Idempotent across ticks. */
    private suspend fun ensureTransactionStarted() {
        if (service.activeTransactionId(chargePointId, connectorId) != null) return
        val connected = chargerConnectionFrom(service.connectorStatus(chargePointId, connectorId)) != ChargerConnection.NotConnected
        if (!connected) return
        logger.info { "OcppCharger $chargePointId: car connected, no transaction open — RemoteStartTransaction" }
        val ok = service.remoteStart(chargePointId, idTag, connectorId)
        if (!ok) logger.warn { "OcppCharger $chargePointId: RemoteStartTransaction not accepted" }
    }

    /** Stop the open transaction when charging is no longer wanted. Idempotent across ticks. */
    private suspend fun ensureTransactionStopped() {
        val txId = service.activeTransactionId(chargePointId, connectorId) ?: return
        logger.info { "OcppCharger $chargePointId: charging off — RemoteStopTransaction($txId)" }
        val ok = service.remoteStop(chargePointId, txId)
        if (!ok) logger.warn { "OcppCharger $chargePointId: RemoteStopTransaction($txId) not accepted" }
    }
}
