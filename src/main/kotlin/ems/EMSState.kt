package io.konektis.ems


data class EMSState(
    val gridPower : Int?,
    val gridVoltage : Int?,
    val chargerPower : Int?,
    val heatpumpPower : Int?,
    val solarPower: Int?,
    val batteryPower : Int?,
    val batteryCharge: Int?
)
