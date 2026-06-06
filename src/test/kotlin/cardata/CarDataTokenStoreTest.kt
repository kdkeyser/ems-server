package io.konektis.cardata

import io.konektis.ocpp.freshTestDb
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CarDataTokenStoreTest {
    @Test
    fun roundTripsRefreshTokenAndGcid() = runTest {
        val store = SqlCarDataTokenStore(freshTestDb()).also { it.init() }
        assertNull(store.get("cid"))

        store.save("cid", refreshToken = "rt-1", gcid = "GCID-1")
        assertEquals(CarDataTokenRecord("cid", "rt-1", "GCID-1"), store.get("cid"))

        // overwrite (rotated refresh token)
        store.save("cid", refreshToken = "rt-2", gcid = "GCID-1")
        assertEquals("rt-2", store.get("cid")!!.refreshToken)
    }
}
