package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Surplus cascade: heat pump -> charger -> battery.
 *
 * The battery soaks up whatever grid imbalance the charger didn't. Because the battery's own power
 * cancels against its contribution to the grid reading, one full correction per tick (deadbeat,
 * [gain] = 1) lands the right setpoint immediately:
 *     target = solar - houseLoad - heatpump - charger
 *
 * That fast, full correction matters. A damped gain < 1 leaves the loop sluggish: after the sun
 * drops it lingers for many ticks still *charging* into a deficit before it ramps down to discharge
 * (observed as "charging the battery while importing from the grid"). Keep [gain] = 1.
 *
 * [gridDeadbandW] ignores imbalances below meter/control noise so the battery doesn't chase a few
 * watts back and forth.
 *
 * NOTE: the deadbeat cancellation is only exact when grid and battery are sampled simultaneously and
 * report instantly. They are not — the SMA smooths its reported power, and grid (P1/HTTP) and
 * battery (Modbus) are polled on independent loops — so on fast ramps the loop can still briefly
 * overshoot and discharge while exporting. Removing that residual needs synchronised sampling, not
 * a lower gain.
 */
class SurplusPriorityStrategy(
    private val gain: Double = 1.0,
    private val gridDeadbandW: Int = 50,
) : Strategy {

    override fun decide(snapshot: WorldSnapshot): ControlDecisions {
        // Available power = what charger + battery currently use, minus any grid import (or plus export).
        // gridPower: negative = exporting (surplus), positive = importing (deficit)
        val available = snapshot.chargerPower.value + snapshot.batteryPower.value - snapshot.gridPower.value

        // Heat pump: unrestricted when surplus, throttled to currently-available headroom on deficit
        val heatpumpMode: ConsumeMode = if (available >= 0) {
            ConsumeMode.Unrestricted
        } else {
            val headroom = max(0, snapshot.heatpumpPower.value + available)
            ConsumeMode.SuggestConsumeUpTo(Watt(headroom))
        }

        // Car charger: forced override (Stop/Fixed) wins; otherwise assign available surplus.
        val chargerAmps = snapshot.chargerOverrideAmps?.coerceIn(0, snapshot.chargerMaxAmps) ?: when {
            available <= 0 -> 0
            else -> {
                val amps = available / 230
                when {
                    amps < snapshot.chargerMinAmps -> 0
                    amps > snapshot.chargerMaxAmps -> snapshot.chargerMaxAmps
                    else -> amps
                }
            }
        }
        val chargerConsumption = chargerAmps * 230

        // Battery soaks up whatever imbalance the charger didn't.
        // projectedGrid = the grid we'd see once the new charger setpoint lands, before the battery moves.
        val projectedGrid = snapshot.gridPower.value + chargerConsumption - snapshot.chargerPower.value
        val batteryTarget = batteryTarget(snapshot.batteryPower.value, projectedGrid)

        return ControlDecisions(
            chargerMaxAmps = chargerAmps,
            batteryCommand = BatteryCommand.SetPower(batteryTarget),
            heatpumpConsumeMode = heatpumpMode
        )
    }

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        batteryTarget(batteryPower.value, gridPower.value)

    /**
     * New battery setpoint = current power minus the correction toward grid = 0, ignoring imbalances
     * inside the deadband. Positive = charge, negative = discharge.
     */
    private fun batteryTarget(batteryPower: Int, gridImbalance: Int): Watt {
        val error = if (abs(gridImbalance) <= gridDeadbandW) 0 else gridImbalance
        return Watt(batteryPower - (gain * error).roundToInt())
    }
}
