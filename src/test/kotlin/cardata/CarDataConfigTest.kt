package io.konektis.cardata

import kotlin.test.*

class CarDataConfigTest {
    @Test
    fun defaultIsDisabledAndValidates() {
        val c = CarDataConfig()
        assertFalse(c.enabled)
        assertEquals("customer.streaming-cardata.bmwgroup.com", c.brokerHost)
        assertEquals(9000, c.brokerPort)
        assertSame(c, c.validated()) // disabled: no required fields
    }

    @Test
    fun enabledWithoutClientIdFails() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CarDataConfig(enabled = true, vin = "WBA1", socDescriptor = "x").validated()
        }
        assertTrue(ex.message!!.contains("clientId"))
    }

    @Test
    fun enabledWithAllFieldsValidates() {
        val c = CarDataConfig(enabled = true, clientId = "cid", vin = "WBA1", socDescriptor = "x")
        assertSame(c, c.validated())
    }
}
