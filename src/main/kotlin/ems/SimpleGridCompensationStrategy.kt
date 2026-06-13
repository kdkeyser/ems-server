package io.konektis.ems

import io.konektis.devices.Watt
import kotlin.math.abs

/**
 * The simplest correct control law: steer *only* the battery so its new power cancels the grid
 * imbalance, leaving the charger and heat pump untouched.
 *
 * Sign conventions (all power: + = consuming/importing, - = producing/exporting):
 *   gridPower    : + = importing (deficit)   - = exporting (surplus)
 *   batteryPower : + = charging              - = discharging
 *
 * Moving the battery by (target - batteryPower) shifts the grid by the same amount, so to drive the
 * grid to zero:
 *     newGrid = gridPower + (target - batteryPower) = 0   =>   target = batteryPower - gridPower
 *
 * Therefore importing (gridPower > 0) makes target more negative -> discharge; exporting makes it
 * more positive -> charge. [gridDeadbandW] holds the battery steady for imbalances below meter noise.
 *
 * This deliberately ignores the surplus cascade. Use it to isolate battery behaviour (and to verify
 * the SMA setpoint sign on real hardware against the per-tick log in EnergyManager).
 */
class SimpleGridCompensationStrategy(
    private val gridDeadbandW: Int = 50,
) : Strategy {

    override fun decide(snapshot: WorldSnapshot): ControlDecisions =
        ControlDecisions(
            chargerCommand = null,        // leave the charger alone
            batteryCommand = BatteryCommand.SetPower(compensate(snapshot.batteryPower, snapshot.gridPower)),
            heatpumpConsumeMode = null,   // leave the heat pump alone
        )

    override fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt =
        compensate(batteryPower, gridPower)

    private fun compensate(batteryPower: Watt, gridPower: Watt): Watt =
        if (abs(gridPower.value) <= gridDeadbandW) batteryPower
        else Watt(batteryPower.value - gridPower.value)
}
