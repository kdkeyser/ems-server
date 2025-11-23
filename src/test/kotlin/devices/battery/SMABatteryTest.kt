package io.konektis.devices.battery

import io.konektis.devices.Watt
import kotlin.test.Test

class SMABatteryTest {
    @Test
    fun getState() {
        val smaBattery = SMABattery("192.168.129.187")
        val state = smaBattery.getState()
        println(state)
    }

    @Test
    fun setCharge() {
        val smaBattery = SMABattery("192.168.129.187")
        smaBattery.setChargingPower(Watt(1000))
        val state = smaBattery.getState()
        println(state)
    }
}