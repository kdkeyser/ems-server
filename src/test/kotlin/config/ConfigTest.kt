package io.konektis.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun warnCfg(
    websocket: WebSocketConfig = WebSocketConfig("user", "s3cret"),
    devices: Devices = Devices(),
) = Config(
    grid = Grid(GridMeterType.P1HomeWizard, GridType.Phase1, "host"),
    devices = devices,
    ocpp = OcppConfig(true, 300, 60),
    websocket = websocket,
)

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

    @Test
    fun clickHouseDefaultsDisabled() {
        val config = loadConfig("/config.yaml", filePath = null)
        assertEquals(false, config.clickhouse.enabled)
        assertEquals("clickhouse", config.clickhouse.host)
        assertEquals(8123, config.clickhouse.port)
        assertEquals("ems", config.clickhouse.database)
    }

    @Test fun `startupWarnings flags the default password`() {
        val warnings = warnCfg(websocket = WebSocketConfig("user", "password")).startupWarnings()
        assertTrue(warnings.any { it.contains("password") })
    }

    @Test fun `startupWarnings is empty for a sane config`() {
        assertTrue(warnCfg().startupWarnings().isEmpty())
    }

    @Test fun `startupWarnings flags multiple chargers`() {
        val two = Devices(charger = listOf(
            Charger(ChargerType.OCPP, "a", chargePointId = "A", chargingCurrent = ChargingCurrent(6.0, 32.0)),
            Charger(ChargerType.OCPP, "b", chargePointId = "B", chargingCurrent = ChargingCurrent(6.0, 32.0)),
        ))
        assertTrue(warnCfg(devices = two).startupWarnings().any { it.contains("Multiple chargers") })
    }

    @Test
    fun clickHouseLoadsFromFile() {
        val yaml = """
            grid:
              type: P1HomeWizard
              gridType: Phase3_400V
              host: 10.9.9.9
            devices:
              charger:
                - type: OCPP
                  name: CP
                  chargePointId: CP01
                  chargingCurrent: { min: 6.0, max: 32.0 }
            ocpp:
              enabled: true
              heartbeatInterval: 300
              connectionTimeout: 60
            clickhouse:
              enabled: true
              host: ch-host
              port: 9000
              database: hist
        """.trimIndent()
        val tmp = File.createTempFile("ems-config-ch", ".yaml")
        tmp.writeText(yaml); tmp.deleteOnExit()
        val config = loadConfig("/config.yaml", filePath = tmp.absolutePath)
        assertEquals(true, config.clickhouse.enabled)
        assertEquals("ch-host", config.clickhouse.host)
        assertEquals(9000, config.clickhouse.port)
        assertEquals("hist", config.clickhouse.database)
    }
}
