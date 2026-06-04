package io.konektis.ems

import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.ws.edgeAuthHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeAuthTest {

    @Test
    fun `edge key set yields the edge header`() {
        val h = edgeAuthHeaders(Settings(edgeKey = "sec"))
        assertEquals("sec", h["X-EMS-Edge-Key"])
        assertEquals(1, h.size)
    }

    @Test
    fun `blank edge key yields no headers`() {
        assertTrue(edgeAuthHeaders(Settings(edgeKey = "")).isEmpty())
        assertTrue(edgeAuthHeaders(Settings()).isEmpty())
    }
}
