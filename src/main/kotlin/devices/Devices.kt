package io.konektis.devices

import java.time.Duration
import kotlin.time.ComparableTimeMark

// units
data class Wh(val value : UInt)
data class Angle(val value: UInt)
data class Watt(val value : Int)
data class Volt(val value : UInt)
data class Ampere(val value: Int)


data class BatteryProperties(val capacity : Wh, val minimalChargeLevel : Wh)
data class SolarProperties(val peakPower : Wh, val orientation: Angle, val inclination: Angle )
data class HeatPumpProperties(val minPower: Watt, val maxPower: Watt, val minSwitchInterval: Duration)



//data class ChargerProperties(val type: GridType, val minCurrentPerPhase: Ampere, val maxCurrentPerPhase: Ampere)

data class DeviceUpdate<T>(val collectedAt: ComparableTimeMark, val update: T)

