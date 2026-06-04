package io.konektis.ems

import io.konektis.ocpp.db.ChargerControlRecord
import io.konektis.ocpp.db.ChargerControlStore

/** In-memory ChargerControlStore for EnergyManager tests. */
class FakeChargerControlStore : ChargerControlStore {
    private var rec: ChargerControlRecord? = null
    override fun init() {}
    override suspend fun get(id: String): ChargerControlRecord? = rec
    override suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean) {
        rec = ChargerControlRecord(id, mode, fixedAmps, charging)
    }
}
