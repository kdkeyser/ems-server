package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Surplus cascade: heat pump -> charger -> battery.
 *
 * The battery is an integrator that drives the grid to zero: each tick its new target is its
 * current power minus the grid imbalance left after the charger has taken its share. Running that
 * integrator at unity gain on lagged, separately-sampled measurements (the SMA reports a smoothed
 * power that trails its own setpoint, and grid/battery are polled on independent cycles) makes it
 * overshoot and hunt around zero — which is exactly the "battery discharging while we export"
 * symptom.
 *
 * Two dampers tame that without pretending a single snapshot can detect the lag (it can't — a
 * correct ramp-down through the export region looks identical to a lag-driven overshoot):
 *  - [gain] < 1 corrects only a fraction of the imbalance per tick, so overshoot decays instead of
 *    ringing.
 *  - [gridDeadbandW] ignores imbalances below meter/control noise, so the battery stops chasing a
 *    few watts back and forth.
 */
class SurplusPriorityStrategy(
    private val gain: Double = 0.5,
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

        // Car charger: assign available power in amps, clamped to [min, max]
        val chargerAmps = when {
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

        // Battery soaks up whatever imbalance the charger didn't, as a damped integrator.
        // projectedGrid = the grid we'd see once the new charger setpoint lands, before the battery moves.
        val projectedGrid = snapshot.gridPower.value + chargerConsumption - snapshot.chargerPower.value
        val batteryTarget = dampedBatteryTarget(snapshot.batteryPower.value, projectedGrid)

        return ControlDecisions(
            chargerMaxAmps = chargerAmps,
            batteryCommand = BatteryCommand.SetPower(batteryTarget),
            heatpumpConsumeMode = heatpumpMode
        )
    }

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        dampedBatteryTarget(batteryPower.value, gridPower.value)

    /**
     * New battery setpoint = current power minus a damped, deadbanded correction toward grid = 0.
     * Positive = charge, negative = discharge.
     */
    private fun dampedBatteryTarget(batteryPower: Int, gridImbalance: Int): Watt {
        val error = if (abs(gridImbalance) <= gridDeadbandW) 0 else gridImbalance
        return Watt(batteryPower - (gain * error).roundToInt())
    }
}
