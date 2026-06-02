package io.konektis.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfigTest {
    @Test
    fun testLoadConfig() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertNotNull(config)
        assertEquals(2, config.devices.solar?.size)
        assertEquals(1, config.devices.heatPump?.size)
        assertEquals(1, config.devices.charger?.size)
    }

    @Test
    fun testDatabaseDefaults() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertEquals("ems.db", config.database.path)
        assertEquals(30, config.ocpp.callTimeoutSeconds)
    }

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            grid:
              type: P1HomeWizard
              gridType: Phase3_400V
              host: 10.9.9.9
            devices:
              charger:
                - type: OCPP
                  name: From File Charger
                  chargePointId: FILE01
                  chargingCurrent: { min: 6.0, max: 32.0 }
            ocpp:
              enabled: true
              heartbeatInterval: 300
              connectionTimeout: 60
            websocket:
              username: fileuser
              password: filepass
            database:
              path: /data/ems.db
        """.trimIndent()
        val tmp = File.createTempFile("ems-config", ".yaml")
        tmp.writeText(yaml)
        tmp.deleteOnExit()

        val config = loadConfig("/config.yaml", filePath = tmp.absolutePath)

        assertEquals("From File Charger", config.devices.charger.first().name)
        assertEquals("/data/ems.db", config.database.path)
        assertEquals("fileuser", config.websocket.username)
    }

    @Test
    fun testFallsBackToResourceWhenFileAbsent() {
        val config = loadConfig("/config.yaml", filePath = "/no/such/ems-config-does-not-exist.yaml")
        // Falls back to the bundled classpath resource (the dev config).
        assertEquals(2, config.devices.solar?.size)
        assertEquals("ems.db", config.database.path)
    }
}
