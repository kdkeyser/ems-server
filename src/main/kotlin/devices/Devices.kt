package io.konektis.devices

import java.time.Duration

// units
data class Wh(val value : UInt)
data class Angle(val value: UInt)
data class W(val value : Int)

data class BatteryProperties(val capacity : Wh, val minimalChargeLevel : Wh)
data class SolarProperties(val peakPower : Wh, val orientation: Angle, val inclination: Angle )
data class ControllableConsumer(val minPower: W, val maxPower: W, val minSwitchInterval: Duration)

