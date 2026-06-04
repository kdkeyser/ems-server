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

        store.setSmartChargingSupported("CP01", true)
        store.setPowerImportSeen("CP01", true)
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

    @Test
    fun recordBootUpdateBranch() = runTest {
        val db = freshTestDb()
        val store = ChargePointStore(db)
        store.init()

        // First boot — insert path
        store.recordBoot("CP01", vendor = "Acme", model = "X1", firmware = "1.0")
        assertEquals("X1", store.get("CP01")!!.model)

        // Second boot — update path; model/firmware should change
        store.recordBoot("CP01", vendor = "Acme", model = "X2", firmware = "2.0")
        assertEquals("X2", store.get("CP01")!!.model)
        assertEquals(1, store.all().size)

        // accepted flag must survive a re-boot
        store.setAccepted("CP01", true)
        assertTrue(store.get("CP01")!!.accepted)
        store.recordBoot("CP01", vendor = "Acme", model = "X2", firmware = "2.0")
        assertTrue(store.get("CP01")!!.accepted)
    }

    @Test
    fun idTagPutOverwrites() = runTest {
        val db = freshTestDb()
        val store = IdTagStore(db)
        store.init()

        store.put("TAG1", "Accepted")
        store.put("TAG1", "Blocked")
        assertEquals("Blocked", store.get("TAG1")?.status)
        assertEquals(1, store.all().size)
    }

    @Test
    fun idTagDelete() = runTest {
        val db = freshTestDb()
        val store = IdTagStore(db)
        store.init()

        store.put("TAG1", "Accepted")
        store.delete("TAG1")
        assertNull(store.get("TAG1"))
    }

    @Test
    fun chargerSettingsPutOverwrites() = runTest {
        val db = freshTestDb()
        val store = ChargerSettingsStore(db)
        store.init()

        store.put("CP01", maxCurrentA = 16, emsAutoControl = true)
        val initial = store.get("CP01")!!
        assertEquals(16, initial.maxCurrentA)
        assertTrue(initial.emsAutoControl)

        // Overwrite — update branch
        store.put("CP01", maxCurrentA = 10, emsAutoControl = false)
        val updated = store.get("CP01")!!
        assertEquals(10, updated.maxCurrentA)
        assertFalse(updated.emsAutoControl)
    }

    @Test
    fun chargerControlRoundTrip() = runTest {
        val db = freshTestDb()
        val store = SqlChargerControlStore(db)
        store.init()

        assertNull(store.get("CP01"))
        store.put("CP01", mode = "FIXED", fixedAmps = 16, charging = true)
        val c = store.get("CP01")!!
        assertEquals("FIXED", c.mode)
        assertEquals(16, c.fixedAmps)
        assertTrue(c.charging)

        store.put("CP01", mode = "SOLAR", fixedAmps = 10, charging = false)
        val c2 = store.get("CP01")!!
        assertEquals("SOLAR", c2.mode)
        assertEquals(10, c2.fixedAmps)
        assertFalse(c2.charging)
    }

    @Test
    fun recentReturnsNewestFirst() = runTest {
        val db = freshTestDb()
        val store = TransactionStore(db)
        store.init()

        store.record(
            transactionId = 1, chargePointId = "CP01", connectorId = 1, idTag = "TAG1",
            meterStart = 0, meterStop = null, startTime = 1000L, stopTime = null, stopReason = null
        )
        store.record(
            transactionId = 2, chargePointId = "CP01", connectorId = 1, idTag = "TAG1",
            meterStart = 0, meterStop = null, startTime = 2000L, stopTime = null, stopReason = null
        )
        store.record(
            transactionId = 3, chargePointId = "CP01", connectorId = 1, idTag = "TAG1",
            meterStart = 0, meterStop = null, startTime = 3000L, stopTime = null, stopReason = null
        )

        val top2 = store.recent(2)
        assertEquals(listOf(3, 2), top2.map { it.transactionId })

        val all = store.recent(10)
        assertEquals(3, all.size)
    }
}
