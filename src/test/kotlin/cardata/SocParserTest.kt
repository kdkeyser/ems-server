package io.konektis.cardata

import kotlin.test.*

class SocParserTest {
    private val desc = "vehicle.drivetrain.electricEngine.charging.soc"

    @Test
    fun extractsIntegerSoc() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":73,"timestamp":"2026-06-06T10:00:00Z"}}}"""
        assertEquals(73, parseSoc(payload, desc))
    }

    @Test
    fun roundsDecimalSoc() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":72.6,"timestamp":"t"}}}"""
        assertEquals(73, parseSoc(payload, desc))
    }

    @Test
    fun acceptsStringNumberValue() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":"50","timestamp":"t"}}}"""
        assertEquals(50, parseSoc(payload, desc))
    }

    @Test
    fun returnsNullWhenDescriptorAbsent() {
        val payload = """{"vin":"WBA1","data":{"some.other.descriptor":{"value":10}}}"""
        assertNull(parseSoc(payload, desc))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(parseSoc("not json", desc))
        assertNull(parseSoc("""{"data":{"$desc":{"value":"NaN"}}}""", desc))
    }
}
