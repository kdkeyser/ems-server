package io.konektis.ocpp.db

import io.konektis.ocpp.freshTestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppStoresTest {

    @Test
    fun chargePointUpsertAndAccept() = runTest {
        val db = freshTestDb()
        val store = ChargePointStore(db)
        store.init()

        store.recordBoot("CP01", vendor = "Acme", model = "X1", firmware = "1.0")
        val cp = store.get("CP01")
        assertNotNull(cp)
        assertEquals("Acme", cp.vendor)
        assertFalse(cp.accepted) // default not accepted until approved

        store.setAccepted("CP01", true)
        assertTrue(store.get("CP01")!!.accepted)

        store.setCapabilities("CP01", smartCharging = true, powerImport = true)
        val updated = store.get("CP01")!!
        assertTrue(updated.smartChargingSupported)
        assertTrue(updated.powerImportSeen)
    }

    @Test
    fun idTagAuthorization() = runTest {
        val db = freshTestDb()
        val store = IdTagStore(db)
        store.init()

        assertNull(store.get("TAG1"))
        store.put("TAG1", "Accepted")
        assertEquals("Accepted", store.get("TAG1")?.status)
    }

    @Test
    fun chargerSettingsRoundTrip() = runTest {
        val db = freshTestDb()
        val store = ChargerSettingsStore(db)
        store.init()

        store.put("CP01", maxCurrentA = 16, emsAutoControl = true)
        val s = store.get("CP01")!!
        assertEquals(16, s.maxCurrentA)
        assertTrue(s.emsAutoControl)
    }

    @Test
    fun transactionInsertAndList() = runTest {
        val db = freshTestDb()
        val store = TransactionStore(db)
        store.init()

        store.record(
            transactionId = 1, chargePointId = "CP01", connectorId = 1, idTag = "TAG1",
            meterStart = 0, meterStop = 1500, startTime = 1000L, stopTime = 2000L, stopReason = "Local"
        )
        val recent = store.recent(10)
        assertEquals(1, recent.size)
        assertEquals(1500, recent.first().meterStop)
    }
}
