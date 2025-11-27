package io.konektis.devices.solar

import io.konektis.devices.Solar.SMASolar
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SMASolarTest {
    @Test
    fun getState() {
        runBlocking {
            val smaSolar = SMASolar("192.168.129.15")
            smaSolar.update()
            val state = smaSolar.getState()
            println(state)
        }
    }


}