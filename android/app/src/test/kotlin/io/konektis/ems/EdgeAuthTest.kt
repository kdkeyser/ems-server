package io.konektis.ems

import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.ws.edgeAuthHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeAuthTest {

    @Test
    fun `both values set yields both CF headers`() {
        val h = edgeAuthHeaders(Settings(cfAccessClientId = "id", cfAccessClientSecret = "sec"))
        assertEquals("id", h["CF-Access-Client-Id"])
        assertEquals("sec", h["CF-Access-Client-Secret"])
        assertEquals(2, h.size)
    }

    @Test
    fun `missing either value yields no headers`() {
        assertTrue(edgeAuthHeaders(Settings(cfAccessClientId = "id")).isEmpty())
        assertTrue(edgeAuthHeaders(Settings(cfAccessClientSecret = "sec")).isEmpty())
        assertTrue(edgeAuthHeaders(Settings()).isEmpty())
    }
}
