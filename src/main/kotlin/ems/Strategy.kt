package io.konektis.ems

import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode

data class WorldSnapshot(
    val gridPower: Watt,          // signed: negative = exporting, positive = importing
    val solarPower: Watt,
    val batteryCharge: UShort,    // state of charge, percent 0-100
    val batteryPower: Watt,       // net power: positive = charging, negative = discharging
    val chargerPower: Watt,
    val heatpumpPower: Watt,
    val chargerMinAmps: Int,      // from config: minimum amps before charger stops
    val chargerMaxAmps: Int       // from config: maximum amps
)

data class ControlDecisions(
    val chargerMaxAmps: Int?,             // null = no change
    val batteryTargetPower: Watt?,        // null = no change; positive=charge, negative=discharge
    val heatpumpConsumeMode: ConsumeMode? // null = no change
)

interface Strategy {
    fun decide(snapshot: WorldSnapshot): ControlDecisions
}
