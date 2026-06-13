package io.konektis.devices

import kotlin.time.ComparableTimeMark

data class Watt(val value: Int)
data class Volt(val value: UInt)
data class Ampere(val value: Int)

data class DeviceUpdate<T>(val collectedAt: ComparableTimeMark, val update: T)

