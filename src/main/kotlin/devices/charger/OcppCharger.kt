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
 */
class OcppCharger(
    private val chargePointId: String,
    private val connectorId: Int,
    private val service: OcppService,
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
        if (!service.isPowerControlCapable(chargePointId)) {
            logger.debug { "OcppCharger $chargePointId: SmartCharging unsupported, skipping setMaxChargerPower" }
            return
        }
        // Send in amps (230V convention). The EMS has already clamped to the config max / fixed level.
        val amps = power.value / 230
        val ok = service.setChargingProfile(chargePointId, connectorId, amps.toDouble(), ChargingRateUnitType.A)
        if (!ok) logger.warn { "OcppCharger $chargePointId: SetChargingProfile($amps A) not accepted" }
    }
}
