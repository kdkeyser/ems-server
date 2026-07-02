package io.konektis.config

import io.konektis.ocpp.freshTestDb
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigServiceTest {
    private fun cfg(
        source: ConfigSource,
        gridHost: String = "192.168.1.1",
        dbPath: String = "ems.db",
        wsPassword: String = "filepass",
    ) = Config(
        grid = Grid(GridMeterType.P1HomeWizard, gridHost),
        devices = Devices(),
        ocpp = OcppConfig(true, 300, 60),
        websocket = WebSocketConfig("user", wsPassword),
        database = DatabaseConfig(dbPath),
        configSource = source,
    )

    @Test
    fun `file mode returns the yaml config unchanged and never touches the DB`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val file = cfg(ConfigSource.file, gridHost = "10.0.0.9")

        val resolved = ConfigService(file, store).resolve()

        assertEquals(file, resolved)
        assertNull(store.load(), "file mode must not write to the store")
    }

    @Test
    fun `database mode seeds the DB from the file on first use and returns the file config`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val file = cfg(ConfigSource.database, gridHost = "10.0.0.9")

        val resolved = ConfigService(file, store).resolve()

        assertEquals("10.0.0.9", resolved.grid.host)
        assertNotNull(store.load(), "database mode must seed the store on first use")
    }

    @Test
    fun `database mode serves the stored document over the file`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val file = cfg(ConfigSource.database, gridHost = "10.0.0.9")
        // Pre-seed the store with a different grid host.
        store.save(ConfigService.DEFAULT_JSON.encodeToString(cfg(ConfigSource.database, gridHost = "172.16.0.5")))

        val resolved = ConfigService(file, store).resolve()

        assertEquals("172.16.0.5", resolved.grid.host)
    }

    @Test
    fun `update in file mode is rejected`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val service = ConfigService(cfg(ConfigSource.file), store).also { it.resolve() }
        assertFailsWith<ConfigReadOnlyException> {
            service.update(cfg(ConfigSource.file, gridHost = "10.9.9.9"))
        }
        assertNull(store.load(), "a rejected update must not write")
    }

    @Test
    fun `update in database mode validates, persists, bumps version and keeps bootstrap`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val service = ConfigService(cfg(ConfigSource.database, dbPath = "/data/ems.db"), store)
        service.resolve()  // seeds, version 1

        val updated = service.update(cfg(ConfigSource.database, gridHost = "10.1.2.3", dbPath = "/tampered.db"))

        assertEquals("10.1.2.3", updated.grid.host)
        assertEquals("/data/ems.db", updated.database.path, "bootstrap stays from the file")
        assertEquals(2, service.version)
        assertEquals(updated, service.current())
    }

    @Test
    fun `update rejects an invalid candidate without persisting`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        val service = ConfigService(cfg(ConfigSource.database), store)
        service.resolve()  // version 1

        val twoBatteries = cfg(ConfigSource.database).copy(
            devices = Devices(battery = listOf(
                Battery(BatteryType.SMA_Sunny_Boy_Storage, "A", "1.1.1.1"),
                Battery(BatteryType.SMA_Sunny_Boy_Storage, "B", "1.1.1.2"),
            ))
        )
        assertFailsWith<ConfigValidationException> { service.update(twoBatteries) }
        assertEquals(1, service.version, "a rejected update must not bump the version")
    }

    @Test
    fun `database mode always keeps bootstrap fields from the file`() {
        val store = ConfigStore(freshTestDb()).also { it.init() }
        // Stored document claims a different DB path and websocket password — both bootstrap.
        store.save(
            ConfigService.DEFAULT_JSON.encodeToString(
                cfg(ConfigSource.database, dbPath = "/tampered/evil.db", wsPassword = "stored-pass")
            )
        )
        val file = cfg(ConfigSource.database, dbPath = "/data/ems.db", wsPassword = "filepass")

        val resolved = ConfigService(file, store).resolve()

        assertEquals("/data/ems.db", resolved.database.path, "DB path is bootstrap — from the file")
        assertEquals("filepass", resolved.websocket.password, "ws password is bootstrap — from the file")
    }
}
