package io.konektis.devices.solar

import io.konektis.devices.Solar.SMASolar
import kotlin.test.*

class SMASolarTest {
    @Test
    fun getState() {
        val smaSolar = SMASolar("192.168.129.25")
        val state = smaSolar.getState()
        println(state)
    }


}