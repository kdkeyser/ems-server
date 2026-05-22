package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlin.math.max

class SurplusPriorityStrategy : Strategy {

    override fun decide(snapshot: WorldSnapshot): ControlDecisions {
        // Available power = what charger + battery currently use, minus any grid import (or plus grid export)
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

        // Battery: remaining surplus charges; remaining deficit discharges
        val chargerConsumption = chargerAmps * 230
        val batteryTarget = Watt(available - chargerConsumption)

        return ControlDecisions(
            chargerMaxAmps = chargerAmps,
            batteryTargetPower = batteryTarget,
            heatpumpConsumeMode = heatpumpMode
        )
    }
}
