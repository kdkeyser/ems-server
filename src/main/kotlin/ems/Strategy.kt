package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.charger.ChargerCommand
import io.konektis.devices.smartConsumers.ConsumeMode

data class WorldSnapshot(
    val gridPower: Watt,          // signed: negative = exporting, positive = importing
    val solarPower: Watt,
    val batteryCharge: UShort,    // state of charge, percent 0-100
    val batteryPower: Watt,       // net power: positive = charging, negative = discharging
    val chargerPower: Watt,
    val heatpumpPower: Watt,
    val chargerMinAmps: Int,      // from config: minimum amps before charger stops
    val chargerMaxAmps: Int,      // from config: maximum amps
    val chargerOverride: ChargerCommand? = null  // non-null = forced command (Stop / Fixed); null = compute from surplus
)

data class ControlDecisions(
    val chargerCommand: ChargerCommand?,  // null = no change
    val batteryCommand: BatteryCommand?,  // null = no change
    val heatpumpConsumeMode: ConsumeMode? // null = no change
)

interface Strategy {
    /** Full-data decision (tier 1). */
    fun decide(snapshot: WorldSnapshot): ControlDecisions

    /** Degraded decision (tier 2): battery target to drive grid → 0 using only grid + battery. */
    fun decideDegraded(gridPower: Watt, batteryPower: Watt): Watt
}
