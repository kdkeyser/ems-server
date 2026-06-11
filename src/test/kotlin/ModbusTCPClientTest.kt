package io.konektis

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ModbusTCPClientTest {

    @Test fun `constructor performs no network IO`() {
        // 203.0.113.1 is TEST-NET-3 (unroutable). With lazy connect the constructor returns
        // immediately; an eager connect would block until the TCP timeout or throw. This is what
        // lets the server start while inverters are offline.
        val elapsed = measureTime { ModbusTCPClient("203.0.113.1") }
        assertTrue(elapsed < 2.seconds, "constructor took $elapsed — is it connecting eagerly?")
    }
}
