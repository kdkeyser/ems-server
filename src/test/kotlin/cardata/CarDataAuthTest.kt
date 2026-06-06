package io.konektis.cardata

import java.time.Instant
import kotlin.test.*

class CarDataAuthTest {
    @Test
    fun parsesDeviceCodeResponse() {
        val r = parseDeviceCodeResponse(
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://x/verify","interval":5,"expires_in":600}"""
        )!!
        assertEquals("dc", r.deviceCode)
        assertEquals("ABCD-1234", r.userCode)
        assertEquals("https://x/verify", r.verificationUri)
        assertEquals(5, r.intervalSeconds)
    }

    @Test
    fun parsesTokenResponse() {
        val r = parseTokenResponse(
            """{"id_token":"idt","refresh_token":"rt","expires_in":3600,"gcid":"GCID-1"}"""
        )!!
        assertEquals("idt", r.idToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(3600, r.expiresInSeconds)
        assertEquals("GCID-1", r.gcid)
    }

    @Test
    fun parseTokenResponseReturnsNullOnError() {
        assertNull(parseTokenResponse("""{"error":"authorization_pending"}"""))
    }

    @Test
    fun needsRefreshHonoursMargin() {
        val now = Instant.parse("2026-06-06T10:00:00Z")
        val expiresSoon = now.plusSeconds(120)
        val expiresLater = now.plusSeconds(600)
        assertTrue(needsRefresh(expiresSoon, now, marginSeconds = 300))
        assertFalse(needsRefresh(expiresLater, now, marginSeconds = 300))
    }
}
