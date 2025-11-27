package io.konektis.devices.battery

import io.konektis.devices.Watt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class SMABatteryTest {
    @Test
    fun getState() {
        runBlocking {
            val smaBattery = SMABattery("192.168.129.187")
            //smaBattery.
            val state = smaBattery.getState()
            println(state)
        }
    }

    @Test
    fun setCharge() {
        runBlocking {
            val smaBattery = SMABattery("192.168.129.187")
            smaBattery.setChargingPower(Watt(1000))
            val state = smaBattery.getState()
            println(state)
        }
    }
}