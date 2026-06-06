package io.konektis.ems

import io.konektis.devices.charger.ChargerConnection

data class EMSState(
    val gridPower : Int?,
    val gridVoltage : Int?,
    val chargerPower : Int?,
    val heatpumpPower : Int?,
    val solarPower: Int?,
    val batteryPower : Int?,
    val batteryCharge: Int?,
    val chargerConnection: ChargerConnection? = null,
    val carCharge: Int? = null,
)
