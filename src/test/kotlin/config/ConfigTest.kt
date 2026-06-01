package io.konektis.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigTest {
    @Test
    fun testLoadConfig() {
        val config = _root_ide_package_.io.konektis.config.loadConfig("/config.yaml")
        assertNotNull(config)
        assertEquals(2, config.devices.solar?.size)
        assertEquals(1, config.devices.heatPump?.size)
        assertEquals(1, config.devices.charger?.size)
    }

    @Test
    fun testDatabaseDefaults() {
        val config = _root_ide_package_.io.konektis.config.loadConfig("/config.yaml")
        assertEquals("ems.db", config.database.path)
        assertEquals(30, config.ocpp.callTimeoutSeconds)
    }
}