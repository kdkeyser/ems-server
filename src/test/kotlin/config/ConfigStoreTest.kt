package io.konektis.config

import io.konektis.ocpp.freshTestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigStoreTest {
    private fun store() = ConfigStore(freshTestDb()).also { it.init() }

    @Test
    fun `load returns null on an empty store`() {
        assertNull(store().load())
    }

    @Test
    fun `save then load round-trips the document at version 1`() {
        val store = store()
        assertEquals(1, store.save("""{"hello":"world"}"""))
        val stored = store.load()!!
        assertEquals("""{"hello":"world"}""", stored.json)
        assertEquals(1, stored.version)
    }

    @Test
    fun `save replaces the single row and bumps the revision`() {
        val store = store()
        store.save("""{"v":1}""")
        assertEquals(2, store.save("""{"v":2}"""))
        val stored = store.load()!!
        assertEquals("""{"v":2}""", stored.json)
        assertEquals(2, stored.version)
    }
}
