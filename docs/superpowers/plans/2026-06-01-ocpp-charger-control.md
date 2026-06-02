# OCPP Charger Control + Local Config Webpage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the OCPP 1.6J server a first-class, EMS-controllable car charger (read power via MeterValues, throttle via SetChargingProfile) with a local LAN webpage to configure allow-list/idTags/charging defaults and view live status, persisting non-time-series data in SQLite.

**Architecture:** Refactor the disconnected `OcppSessionManager` into a DI-provided `OcppService` that holds live session state, persists to SQLite via Exposed, correlates outbound CALL/CALL_RESULT, and detects charger capabilities at runtime. An `OcppCharger` implements the existing `Charger` interface so `SurplusPriorityStrategy` throttles it exactly like `Webasto`, falling back to session-only when a charger lacks SmartCharging. A `/ocpp-ui` webpage (static HTML + live WS + REST) provides config and status.

**Tech Stack:** Kotlin, Ktor (server + WebSockets), kotlinx.serialization, Exposed + SQLite (xerial sqlite-jdbc), kotlin-inject DI, klogging, kotlin.test + MockK + ktor-server-test-host.

**Reference spec:** `docs/superpowers/specs/2026-06-01-ocpp-charger-control-design.md`

---

## Conventions used throughout

- Run a single test class: `./gradlew test --tests "io.konektis.<pkg>.<Class>"`
- Run everything: `./gradlew build`
- Power sign convention: negative = producing/exporting, positive = consuming/importing.
- Charger connection endpoint stays `/ocpp/{chargePointId}`. The webpage + REST live under `/ocpp-ui` to avoid `{chargePointId}` route capture.

---

## Phase 0 — SQLite foundation

### Task 1: Add SQLite dependency and provide a shared `Database`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts:36-38`
- Modify: `src/main/kotlin/config/Config.kt`
- Modify: `src/main/resources/config.yaml`
- Create: `src/main/kotlin/ocpp/db/Db.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`
- Modify: `src/main/kotlin/di/AppComponent.kt`
- Test: `src/test/kotlin/config/ConfigTest.kt`

- [ ] **Step 1: Add the SQLite driver to the version catalog**

In `gradle/libs.versions.toml`, under `[libraries]` (after the `h2 = ...` line):

```toml
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version = "3.45.3.0" }
```

- [ ] **Step 2: Depend on SQLite in the build**

In `build.gradle.kts`, immediately after `implementation(libs.h2)` (line 38):

```kotlin
    implementation(libs.sqlite.jdbc)
```

- [ ] **Step 3: Write the failing config test for the new database section**

In `src/test/kotlin/config/ConfigTest.kt`, add inside `ConfigTest`:

```kotlin
    @Test
    fun testDatabaseDefaults() {
        val config = _root_ide_package_.io.konektis.config.loadConfig("/config.yaml")
        assertEquals("ems.db", config.database.path)
        assertEquals(30, config.ocpp.callTimeoutSeconds)
    }
```

- [ ] **Step 4: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: FAIL — `Config` has no `database` property / `OcppConfig` has no `callTimeoutSeconds`.

- [ ] **Step 5: Add `DatabaseConfig`, extend `OcppConfig`, wire into `Config`**

In `src/main/kotlin/config/Config.kt`, replace the `OcppConfig` declaration and add `DatabaseConfig`:

```kotlin
@Serializable
data class OcppConfig(
    val enabled: Boolean,
    val heartbeatInterval: Int,
    val connectionTimeout: Int,
    val callTimeoutSeconds: Int = 30,
    val acceptUnknownChargePoints: Boolean = true,
    val acceptUnknownIdTags: Boolean = true,
    val autoProbeOnBoot: Boolean = true,
)

@Serializable
data class DatabaseConfig(val path: String = "ems.db")
```

Then add the field to `Config`:

```kotlin
@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val database: DatabaseConfig = DatabaseConfig(),
    val refreshThreads : Int = 50
)
```

- [ ] **Step 6: Add the database section to `config.yaml`**

In `src/main/resources/config.yaml`, after the `ocpp:` block add:

```yaml
database:
  path: ems.db
```

- [ ] **Step 7: Create the DB connection + suspend query helper**

Create `src/main/kotlin/ocpp/db/Db.kt`:

```kotlin
package io.konektis.ocpp.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Open a file-based SQLite database for all non-time-series persistence. */
fun openDatabase(path: String): Database =
    Database.connect(url = "jdbc:sqlite:$path", driver = "org.sqlite.JDBC")

/** Run an Exposed transaction on the IO dispatcher (SQLite calls are blocking). */
suspend fun <T> dbQuery(db: Database, block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO, db) { block() }
```

- [ ] **Step 8: Provide the `Database` via DI**

In `src/main/kotlin/di/AppModule.kt`, add the import and a provider. Add near the other imports:

```kotlin
import io.konektis.ocpp.db.openDatabase
import org.jetbrains.exposed.sql.Database
```

Add inside the `AppModule` interface:

```kotlin
    @ApplicationScope
    @Provides
    fun provideDatabase(config: Config): Database = openDatabase(config.database.path)
```

In `src/main/kotlin/di/AppComponent.kt`, expose it so it is constructed eagerly. Add the import `import org.jetbrains.exposed.sql.Database` and add to the abstract members:

```kotlin
    abstract val database: Database
```

- [ ] **Step 9: Run the config test — it should pass**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/main/kotlin/config/Config.kt \
  src/main/resources/config.yaml src/main/kotlin/ocpp/db/Db.kt \
  src/main/kotlin/di/AppModule.kt src/main/kotlin/di/AppComponent.kt \
  src/test/kotlin/config/ConfigTest.kt
git commit -m "feat(ocpp): add SQLite database config and DI-provided connection"
```

---

## Phase 1 — Persistence stores + robust OcppService

### Task 2: OCPP persistence tables and stores

**Files:**
- Create: `src/test/kotlin/ocpp/TestDb.kt`
- Create: `src/main/kotlin/ocpp/db/OcppTables.kt`
- Create: `src/main/kotlin/ocpp/db/OcppStores.kt`
- Test: `src/test/kotlin/ocpp/db/OcppStoresTest.kt`

- [ ] **Step 0: Create a shared test-DB helper (temp file, not `:memory:`)**

A SQLite `:memory:` DB is dropped whenever no connection is open, and Exposed opens/closes a connection per transaction — so in-memory test DBs are flaky. Use a throwaway temp file instead. Create `src/test/kotlin/ocpp/TestDb.kt`:

```kotlin
package io.konektis.ocpp

import org.jetbrains.exposed.sql.Database
import java.io.File

/** A fresh, isolated, file-backed SQLite database for a single test. Deleted on JVM exit. */
fun freshTestDb(): Database {
    val file = File.createTempFile("ocpp-test-", ".db").apply { deleteOnExit() }
    return Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
}
```

- [ ] **Step 1: Write the failing store test**

Create `src/test/kotlin/ocpp/db/OcppStoresTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.db.OcppStoresTest"`
Expected: FAIL — store classes do not exist.

- [ ] **Step 3: Define the Exposed tables**

Create `src/main/kotlin/ocpp/db/OcppTables.kt`:

```kotlin
package io.konektis.ocpp.db

import org.jetbrains.exposed.sql.Table

object OcppChargePoints : Table("ocpp_charge_points") {
    val chargePointId = varchar("charge_point_id", 64)
    val accepted = bool("accepted").default(false)
    val vendor = varchar("vendor", 128).nullable()
    val model = varchar("model", 128).nullable()
    val firmware = varchar("firmware", 128).nullable()
    val smartChargingSupported = bool("smart_charging_supported").default(false)
    val powerImportSeen = bool("power_import_seen").default(false)
    val lastBootAt = long("last_boot_at").nullable()
    override val primaryKey = PrimaryKey(chargePointId)
}

object OcppIdTags : Table("ocpp_id_tags") {
    val idTag = varchar("id_tag", 64)
    val status = varchar("status", 32)
    val expiryDate = varchar("expiry_date", 64).nullable()
    override val primaryKey = PrimaryKey(idTag)
}

object OcppChargerSettings : Table("ocpp_charger_settings") {
    val chargePointId = varchar("charge_point_id", 64)
    val maxCurrentA = integer("max_current_a")
    val emsAutoControl = bool("ems_auto_control").default(true)
    override val primaryKey = PrimaryKey(chargePointId)
}

object OcppTransactions : Table("ocpp_transactions") {
    val id = integer("id").autoIncrement()
    val transactionId = integer("transaction_id")
    val chargePointId = varchar("charge_point_id", 64)
    val connectorId = integer("connector_id")
    val idTag = varchar("id_tag", 64).nullable()
    val meterStart = integer("meter_start")
    val meterStop = integer("meter_stop").nullable()
    val startTime = long("start_time")
    val stopTime = long("stop_time").nullable()
    val stopReason = varchar("stop_reason", 32).nullable()
    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 4: Implement the stores**

Create `src/main/kotlin/ocpp/db/OcppStores.kt`:

```kotlin
package io.konektis.ocpp.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ChargePointRecord(
    val chargePointId: String,
    val accepted: Boolean,
    val vendor: String?,
    val model: String?,
    val firmware: String?,
    val smartChargingSupported: Boolean,
    val powerImportSeen: Boolean,
)

class ChargePointStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppChargePoints) }

    private fun row(r: ResultRow) = ChargePointRecord(
        chargePointId = r[OcppChargePoints.chargePointId],
        accepted = r[OcppChargePoints.accepted],
        vendor = r[OcppChargePoints.vendor],
        model = r[OcppChargePoints.model],
        firmware = r[OcppChargePoints.firmware],
        smartChargingSupported = r[OcppChargePoints.smartChargingSupported],
        powerImportSeen = r[OcppChargePoints.powerImportSeen],
    )

    suspend fun get(id: String): ChargePointRecord? = dbQuery(db) {
        OcppChargePoints.selectAll().where { OcppChargePoints.chargePointId eq id }
            .singleOrNull()?.let(::row)
    }

    suspend fun all(): List<ChargePointRecord> = dbQuery(db) {
        OcppChargePoints.selectAll().map(::row)
    }

    suspend fun recordBoot(id: String, vendor: String?, model: String?, firmware: String?) = dbQuery(db) {
        val exists = OcppChargePoints.selectAll()
            .where { OcppChargePoints.chargePointId eq id }.any()
        if (exists) {
            OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) {
                it[OcppChargePoints.vendor] = vendor
                it[OcppChargePoints.model] = model
                it[OcppChargePoints.firmware] = firmware
                it[lastBootAt] = System.currentTimeMillis()
            }
        } else {
            OcppChargePoints.insert {
                it[chargePointId] = id
                it[accepted] = false
                it[OcppChargePoints.vendor] = vendor
                it[OcppChargePoints.model] = model
                it[OcppChargePoints.firmware] = firmware
                it[lastBootAt] = System.currentTimeMillis()
            }
        }
        Unit
    }

    suspend fun setAccepted(id: String, value: Boolean) = dbQuery(db) {
        OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) { it[accepted] = value }
        Unit
    }

    suspend fun setCapabilities(id: String, smartCharging: Boolean, powerImport: Boolean) = dbQuery(db) {
        OcppChargePoints.update({ OcppChargePoints.chargePointId eq id }) {
            it[smartChargingSupported] = smartCharging
            it[powerImportSeen] = powerImport
        }
        Unit
    }
}

@Serializable
data class IdTagRecord(val idTag: String, val status: String, val expiryDate: String?)

class IdTagStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppIdTags) }

    suspend fun get(idTag: String): IdTagRecord? = dbQuery(db) {
        OcppIdTags.selectAll().where { OcppIdTags.idTag eq idTag }.singleOrNull()?.let {
            IdTagRecord(it[OcppIdTags.idTag], it[OcppIdTags.status], it[OcppIdTags.expiryDate])
        }
    }

    suspend fun all(): List<IdTagRecord> = dbQuery(db) {
        OcppIdTags.selectAll().map { IdTagRecord(it[OcppIdTags.idTag], it[OcppIdTags.status], it[OcppIdTags.expiryDate]) }
    }

    suspend fun put(idTag: String, status: String, expiryDate: String? = null) = dbQuery(db) {
        val exists = OcppIdTags.selectAll().where { OcppIdTags.idTag eq idTag }.any()
        if (exists) {
            OcppIdTags.update({ OcppIdTags.idTag eq idTag }) {
                it[OcppIdTags.status] = status; it[OcppIdTags.expiryDate] = expiryDate
            }
        } else {
            OcppIdTags.insert {
                it[OcppIdTags.idTag] = idTag; it[OcppIdTags.status] = status; it[OcppIdTags.expiryDate] = expiryDate
            }
        }
        Unit
    }

    suspend fun delete(idTag: String) = dbQuery(db) {
        OcppIdTags.deleteWhere { OcppIdTags.idTag eq idTag }; Unit
    }
}

@Serializable
data class ChargerSettingsRecord(val chargePointId: String, val maxCurrentA: Int, val emsAutoControl: Boolean)

class ChargerSettingsStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppChargerSettings) }

    suspend fun get(id: String): ChargerSettingsRecord? = dbQuery(db) {
        OcppChargerSettings.selectAll().where { OcppChargerSettings.chargePointId eq id }.singleOrNull()?.let {
            ChargerSettingsRecord(it[OcppChargerSettings.chargePointId], it[OcppChargerSettings.maxCurrentA], it[OcppChargerSettings.emsAutoControl])
        }
    }

    suspend fun put(id: String, maxCurrentA: Int, emsAutoControl: Boolean) = dbQuery(db) {
        val exists = OcppChargerSettings.selectAll().where { OcppChargerSettings.chargePointId eq id }.any()
        if (exists) {
            OcppChargerSettings.update({ OcppChargerSettings.chargePointId eq id }) {
                it[OcppChargerSettings.maxCurrentA] = maxCurrentA
                it[OcppChargerSettings.emsAutoControl] = emsAutoControl
            }
        } else {
            OcppChargerSettings.insert {
                it[chargePointId] = id
                it[OcppChargerSettings.maxCurrentA] = maxCurrentA
                it[OcppChargerSettings.emsAutoControl] = emsAutoControl
            }
        }
        Unit
    }
}

@Serializable
data class TransactionRecord(
    val transactionId: Int, val chargePointId: String, val connectorId: Int, val idTag: String?,
    val meterStart: Int, val meterStop: Int?, val startTime: Long, val stopTime: Long?, val stopReason: String?,
)

class TransactionStore(private val db: Database) {
    fun init() = transaction(db) { SchemaUtils.create(OcppTransactions) }

    suspend fun record(
        transactionId: Int, chargePointId: String, connectorId: Int, idTag: String?,
        meterStart: Int, meterStop: Int?, startTime: Long, stopTime: Long?, stopReason: String?,
    ) = dbQuery(db) {
        OcppTransactions.insert {
            it[OcppTransactions.transactionId] = transactionId
            it[OcppTransactions.chargePointId] = chargePointId
            it[OcppTransactions.connectorId] = connectorId
            it[OcppTransactions.idTag] = idTag
            it[OcppTransactions.meterStart] = meterStart
            it[OcppTransactions.meterStop] = meterStop
            it[OcppTransactions.startTime] = startTime
            it[OcppTransactions.stopTime] = stopTime
            it[OcppTransactions.stopReason] = stopReason
        }
        Unit
    }

    suspend fun recent(limit: Int): List<TransactionRecord> = dbQuery(db) {
        OcppTransactions.selectAll().orderBy(OcppTransactions.id, SortOrder.DESC).limit(limit).map {
            TransactionRecord(
                it[OcppTransactions.transactionId], it[OcppTransactions.chargePointId], it[OcppTransactions.connectorId],
                it[OcppTransactions.idTag], it[OcppTransactions.meterStart], it[OcppTransactions.meterStop],
                it[OcppTransactions.startTime], it[OcppTransactions.stopTime], it[OcppTransactions.stopReason],
            )
        }
    }
}
```

Note: import `org.jetbrains.exposed.sql.SqlExpressionBuilder.eq` and `org.jetbrains.exposed.sql.deleteWhere` are resolved via the wildcard `org.jetbrains.exposed.sql.*` plus the explicit `eq` import shown.

- [ ] **Step 5: Run the store tests — should pass**

Run: `./gradlew test --tests "io.konektis.ocpp.db.OcppStoresTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ocpp/db/OcppTables.kt src/main/kotlin/ocpp/db/OcppStores.kt \
  src/test/kotlin/ocpp/db/OcppStoresTest.kt
git commit -m "feat(ocpp): SQLite tables and stores for charge points, idTags, settings, transactions"
```

---

### Task 3: Refactor `OcppSessionManager` → `OcppService` (state, stores, klogging, suspend handlers)

This renames and reshapes the session manager. Outbound correlation and capability detection arrive in Tasks 4–6; this task focuses on inbound handling + persistence + state.

**Files:**
- Create: `src/main/kotlin/ocpp/OcppService.kt` (replaces `OcppSessionManager.kt`)
- Delete: `src/main/kotlin/ocpp/OcppSessionManager.kt`
- Modify: `src/main/kotlin/ocpp/OcppServer.kt`
- Modify: `src/main/kotlin/Application.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`, `src/main/kotlin/di/AppComponent.kt`
- Test: rewrite `src/test/kotlin/ocpp/OcppSessionManagerTest.kt` → `src/test/kotlin/ocpp/OcppServiceTest.kt`
- Modify: `src/test/kotlin/ocpp/OcppServerTest.kt`

- [ ] **Step 1: Write the failing service test**

Create `src/test/kotlin/ocpp/OcppServiceTest.kt` (and delete the old `OcppSessionManagerTest.kt` in Step 8):

```kotlin
package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import kotlin.test.*

class OcppServiceTest {

    private fun newService(acceptCp: Boolean = true, acceptTags: Boolean = true): OcppService {
        val db = freshTestDb()
        val cfg = OcppConfig(enabled = true, heartbeatInterval = 300, connectionTimeout = 60,
            callTimeoutSeconds = 1, acceptUnknownChargePoints = acceptCp, acceptUnknownIdTags = acceptTags,
            autoProbeOnBoot = false)
        return OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db), cfg)
            .also { it.initStores() }
    }

    @Test
    fun bootAcceptsWhenAutoAcceptOn() = runTest {
        val svc = newService(acceptCp = true)
        svc.registerSession("CP01", mockk(relaxed = true))
        val resp = svc.handleBootNotification("CP01", BootNotificationRequest("Acme", "X1"))
        assertEquals(RegistrationStatus.Accepted, resp.status)
        assertEquals(300, resp.interval)
    }

    @Test
    fun bootPendingWhenAutoAcceptOff() = runTest {
        val svc = newService(acceptCp = false)
        svc.registerSession("CP02", mockk(relaxed = true))
        val resp = svc.handleBootNotification("CP02", BootNotificationRequest("Acme", "X1"))
        assertEquals(RegistrationStatus.Pending, resp.status)
    }

    @Test
    fun startTransactionPersistsOnStop() = runTest {
        val svc = newService()
        svc.registerSession("CP03", mockk(relaxed = true))
        val start = svc.handleStartTransaction("CP03",
            StartTransactionRequest(connectorId = 1, idTag = "TAG1", meterStart = 0, timestamp = "2026-01-01T00:00:00Z"))
        svc.handleStopTransaction("CP03",
            StopTransactionRequest(transactionId = start.transactionId, timestamp = "2026-01-01T01:00:00Z", meterStop = 1000))
        val recent = svc.recentTransactions(10)
        assertEquals(1, recent.size)
        assertEquals(1000, recent.first().meterStop)
    }

    @Test
    fun statusNotificationUpdatesLiveState() = runTest {
        val svc = newService()
        svc.registerSession("CP04", mockk(relaxed = true))
        svc.handleStatusNotification("CP04",
            StatusNotificationRequest(connectorId = 1, errorCode = ChargePointErrorCode.NoError, status = ChargePointStatus.Available))
        val cp = svc.stateFlow.value.chargePoints.single { it.chargePointId == "CP04" }
        assertEquals("Available", cp.connectors.single().status)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: FAIL — `OcppService` does not exist.

- [ ] **Step 3: Create `OcppService.kt` with state, stores, klogging, suspend handlers**

Create `src/main/kotlin/ocpp/OcppService.kt`. (This supersedes `OcppSessionManager.kt`; correlation/outbound/capability methods are added in later tasks — leave the existing `sendRemoteStartTransaction`/`sendRemoteStopTransaction`/`sendReset` OUT for now; they return in Task 5.)

```kotlin
package io.konektis.ocpp

import io.klogging.Klogging
import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ---- Live (in-memory) state ----

class ConnectorState(
    val connectorId: Int,
    var status: ChargePointStatus = ChargePointStatus.Available,
    var errorCode: ChargePointErrorCode = ChargePointErrorCode.NoError,
    var currentTransactionId: Int? = null,
    var lastPowerW: Int? = null,
)

class ChargePointSession(
    val chargePointId: String,
    val session: DefaultWebSocketSession,
    var vendor: String? = null,
    var model: String? = null,
    var smartChargingSupported: Boolean = false,
    var powerImportSeen: Boolean = false,
    var registrationStatus: RegistrationStatus = RegistrationStatus.Pending,
    val connectors: MutableMap<Int, ConnectorState> = ConcurrentHashMap(),
    val activeTransactions: MutableMap<Int, ActiveTransaction> = ConcurrentHashMap(),
)

data class ActiveTransaction(
    val transactionId: Int, val connectorId: Int, val idTag: String, val startTime: Instant, val meterStart: Int,
)

// ---- Serializable view models pushed to the webpage ----

@Serializable
data class OcppState(val chargePoints: List<OcppChargePointView>)

@Serializable
data class OcppChargePointView(
    val chargePointId: String,
    val online: Boolean,
    val vendor: String?,
    val model: String?,
    val smartChargingSupported: Boolean,
    val powerReadable: Boolean,
    val connectors: List<OcppConnectorView>,
)

@Serializable
data class OcppConnectorView(val connectorId: Int, val status: String, val powerW: Int?, val transactionId: Int?)

class OcppService(
    private val chargePoints: ChargePointStore,
    private val idTags: IdTagStore,
    private val settings: ChargerSettingsStore,
    private val transactions: TransactionStore,
    private val config: OcppConfig,
) : Klogging {

    private val sessions = ConcurrentHashMap<String, ChargePointSession>()
    private val transactionIdCounter = AtomicInteger(1)

    private val _stateFlow = MutableStateFlow(OcppState(emptyList()))
    val stateFlow: StateFlow<OcppState> = _stateFlow.asStateFlow()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun initStores() {
        chargePoints.init(); idTags.init(); settings.init(); transactions.init()
    }

    fun getSession(id: String): ChargePointSession? = sessions[id]

    suspend fun registerSession(chargePointId: String, session: DefaultWebSocketSession) {
        sessions[chargePointId] = ChargePointSession(chargePointId, session)
        logger.info("Registered charge point $chargePointId")
        recomputeState()
    }

    suspend fun unregisterSession(chargePointId: String) {
        sessions.remove(chargePointId)
        logger.info("Unregistered charge point $chargePointId")
        recomputeState()
    }

    suspend fun handleBootNotification(chargePointId: String, request: BootNotificationRequest): BootNotificationResponse {
        logger.info("BootNotification $chargePointId vendor=${request.chargePointVendor} model=${request.chargePointModel}")
        chargePoints.recordBoot(chargePointId, request.chargePointVendor, request.chargePointModel, request.firmwareVersion)
        val existing = chargePoints.get(chargePointId)
        val accepted = when {
            existing?.accepted == true -> true
            config.acceptUnknownChargePoints -> { chargePoints.setAccepted(chargePointId, true); true }
            else -> false
        }
        val status = if (accepted) RegistrationStatus.Accepted else RegistrationStatus.Pending
        sessions[chargePointId]?.apply {
            vendor = request.chargePointVendor
            model = request.chargePointModel
            registrationStatus = status
            smartChargingSupported = existing?.smartChargingSupported ?: false
            powerImportSeen = existing?.powerImportSeen ?: false
        }
        recomputeState()
        return BootNotificationResponse(status, currentTimestamp(), config.heartbeatInterval)
    }

    fun handleHeartbeat(chargePointId: String): HeartbeatResponse = HeartbeatResponse(currentTimestamp())

    suspend fun handleAuthorize(chargePointId: String, request: AuthorizeRequest): AuthorizeResponse =
        AuthorizeResponse(IdTagInfo(status = authorizeTag(request.idTag)))

    private suspend fun authorizeTag(idTag: String): AuthorizationStatus {
        if (idTag.isBlank()) return AuthorizationStatus.Invalid
        val record = idTags.get(idTag)
        return when {
            record != null -> runCatching { AuthorizationStatus.valueOf(record.status) }.getOrDefault(AuthorizationStatus.Invalid)
            config.acceptUnknownIdTags -> AuthorizationStatus.Accepted
            else -> AuthorizationStatus.Invalid
        }
    }

    suspend fun handleStartTransaction(chargePointId: String, request: StartTransactionRequest): StartTransactionResponse {
        val transactionId = transactionIdCounter.getAndIncrement()
        sessions[chargePointId]?.let { s ->
            s.activeTransactions[transactionId] =
                ActiveTransaction(transactionId, request.connectorId, request.idTag, Instant.now(), request.meterStart)
            s.connectors.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }.apply {
                currentTransactionId = transactionId
                status = ChargePointStatus.Charging
            }
        }
        recomputeState()
        return StartTransactionResponse(transactionId, IdTagInfo(status = authorizeTag(request.idTag)))
    }

    suspend fun handleStopTransaction(chargePointId: String, request: StopTransactionRequest): StopTransactionResponse {
        val s = sessions[chargePointId]
        val tx = s?.activeTransactions?.remove(request.transactionId)
        if (tx != null) {
            s.connectors[tx.connectorId]?.apply { currentTransactionId = null; status = ChargePointStatus.Available }
            transactions.record(
                transactionId = tx.transactionId, chargePointId = chargePointId, connectorId = tx.connectorId,
                idTag = tx.idTag, meterStart = tx.meterStart, meterStop = request.meterStop,
                startTime = tx.startTime.toEpochMilli(), stopTime = Instant.now().toEpochMilli(),
                stopReason = request.reason?.name,
            )
        }
        recomputeState()
        return StopTransactionResponse(IdTagInfo(status = AuthorizationStatus.Accepted))
    }

    suspend fun handleStatusNotification(chargePointId: String, request: StatusNotificationRequest): StatusNotificationResponse {
        sessions[chargePointId]?.connectors?.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }?.apply {
            status = request.status; errorCode = request.errorCode
        }
        recomputeState()
        return StatusNotificationResponse()
    }

    suspend fun handleMeterValues(chargePointId: String, request: MeterValuesRequest): MeterValuesResponse {
        val powerW = extractActivePowerW(request)
        if (powerW != null) {
            sessions[chargePointId]?.connectors?.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }?.lastPowerW = powerW
            recomputeState()
        }
        return MeterValuesResponse()
    }

    /** Pull Power.Active.Import (W) from the sampled values, if present. */
    private fun extractActivePowerW(request: MeterValuesRequest): Int? {
        for (mv in request.meterValue) for (sv in mv.sampledValue) {
            if (sv.measurand == Measurand.`Power.Active.Import`) {
                val v = sv.value.toDoubleOrNull() ?: continue
                val watts = if (sv.unit == UnitOfMeasure.kW) v * 1000 else v
                return watts.toInt()
            }
        }
        return null
    }

    suspend fun handleDataTransfer(chargePointId: String, request: DataTransferRequest): DataTransferResponse =
        DataTransferResponse(status = DataTransferStatus.Accepted, data = null)

    suspend fun recentTransactions(limit: Int): List<TransactionRecord> = transactions.recent(limit)

    /** Latest active-power reading (W) for a connector, or null until one arrives. */
    fun latestPowerW(chargePointId: String, connectorId: Int): Int? =
        sessions[chargePointId]?.connectors?.get(connectorId)?.lastPowerW

    private fun recomputeState() {
        _stateFlow.value = OcppState(
            sessions.values.map { s ->
                OcppChargePointView(
                    chargePointId = s.chargePointId,
                    online = true,
                    vendor = s.vendor,
                    model = s.model,
                    smartChargingSupported = s.smartChargingSupported,
                    powerReadable = s.powerImportSeen,
                    connectors = s.connectors.values.map { c ->
                        OcppConnectorView(c.connectorId, c.status.name, c.lastPowerW, c.currentTransactionId)
                    }.sortedBy { it.connectorId },
                )
            }.sortedBy { it.chargePointId },
        )
    }

    private fun currentTimestamp(): String =
        Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
```

- [ ] **Step 4: Rewrite `OcppServer.kt` to take an injected service + subprotocol**

Replace `src/main/kotlin/ocpp/OcppServer.kt` with:

```kotlin
package io.konektis.ocpp

import io.klogging.logger
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.*

private const val OCPP_SUBPROTOCOL = "ocpp1.6"

/** Wire the OCPP charge-point WebSocket endpoint. WebSockets plugin is installed in configureSockets. */
fun Application.configureOcppServer(service: OcppService) {
    val handler = OcppMessageHandler(service)
    routing {
        webSocket("/ocpp/{chargePointId}", protocol = OCPP_SUBPROTOCOL) {
            val chargePointId = call.parameters["chargePointId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID")); return@webSocket
            }
            handler.handleConnection(chargePointId, this)
        }
        webSocket("/ocpp/1.6/{chargePointId}", protocol = OCPP_SUBPROTOCOL) {
            val chargePointId = call.parameters["chargePointId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID")); return@webSocket
            }
            handler.handleConnection(chargePointId, this)
        }
    }
}

class OcppMessageHandler(private val service: OcppService) {
    private val log = logger("io.konektis.ocpp.handler")
    private val json = service.json

    suspend fun handleConnection(chargePointId: String, session: DefaultWebSocketSession) {
        log.info("New OCPP connection from {cp}", chargePointId)
        try {
            service.registerSession(chargePointId, session)
            session.incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    log.debug("recv from {cp}: {msg}", chargePointId, text)
                    try {
                        processMessage(chargePointId, text)?.let { session.send(Frame.Text(it)) }
                    } catch (e: Exception) {
                        log.error("error handling message from {cp}: {err}", chargePointId, e.message)
                        val messageId = runCatching {
                            Json.parseToJsonElement(text).jsonArray[1].jsonPrimitive.content
                        }.getOrDefault("unknown")
                        session.send(Frame.Text(errorResponse(messageId, ErrorCode.InternalError, e.message ?: "error")))
                    }
                }
            }
        } catch (e: Exception) {
            log.error("connection error for {cp}: {err}", chargePointId, e.message)
        } finally {
            service.unregisterSession(chargePointId)
            log.info("OCPP connection closed for {cp}", chargePointId)
        }
    }

    private suspend fun processMessage(chargePointId: String, message: String): String? {
        val arr = Json.parseToJsonElement(message).jsonArray
        require(arr.size >= 3) { "Invalid OCPP message format" }
        val messageTypeId = arr[0].jsonPrimitive.int
        val uniqueId = arr[1].jsonPrimitive.content
        return when (messageTypeId) {
            MessageType.CALL.value -> {
                val action = arr[2].jsonPrimitive.content
                val payload = if (arr.size > 3) arr[3].jsonObject else JsonObject(emptyMap())
                handleCall(chargePointId, uniqueId, action, payload)
            }
            MessageType.CALL_RESULT.value -> {
                val payload = if (arr.size > 2) arr[2].jsonObject else JsonObject(emptyMap())
                service.completeCall(uniqueId, payload); null
            }
            MessageType.CALL_ERROR.value -> {
                val code = if (arr.size > 2) arr[2].jsonPrimitive.content else "Unknown"
                val desc = if (arr.size > 3) arr[3].jsonPrimitive.content else ""
                service.failCall(uniqueId, "$code: $desc"); null
            }
            else -> throw IllegalArgumentException("Unknown message type: $messageTypeId")
        }
    }

    private suspend fun handleCall(chargePointId: String, uniqueId: String, action: String, payload: JsonObject): String {
        val responsePayload: JsonObject = when (action) {
            Action.BootNotification.name ->
                json.encodeToJsonElement(service.handleBootNotification(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.Heartbeat.name ->
                json.encodeToJsonElement(service.handleHeartbeat(chargePointId)).jsonObject
            Action.Authorize.name ->
                json.encodeToJsonElement(service.handleAuthorize(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StartTransaction.name ->
                json.encodeToJsonElement(service.handleStartTransaction(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StopTransaction.name ->
                json.encodeToJsonElement(service.handleStopTransaction(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.StatusNotification.name ->
                json.encodeToJsonElement(service.handleStatusNotification(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.MeterValues.name ->
                json.encodeToJsonElement(service.handleMeterValues(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            Action.DataTransfer.name ->
                json.encodeToJsonElement(service.handleDataTransfer(chargePointId, json.decodeFromJsonElement(payload))).jsonObject
            else -> return errorResponse(uniqueId, ErrorCode.NotSupported, "Action $action is not supported")
        }
        return buildJsonArray { add(MessageType.CALL_RESULT.value); add(uniqueId); add(responsePayload) }.toString()
    }

    private fun errorResponse(uniqueId: String, code: ErrorCode, desc: String): String =
        buildJsonArray { add(MessageType.CALL_ERROR.value); add(uniqueId); add(code.name); add(desc); add(JsonObject(emptyMap())) }.toString()
}
```

Note: `service.completeCall` / `service.failCall` are added in Task 4. To keep this task compiling on its own, add temporary stubs to `OcppService` now:

```kotlin
    // Replaced with real correlation in Task 4.
    fun completeCall(uniqueId: String, payload: JsonObject) {}
    fun failCall(uniqueId: String, reason: String) {}
```

- [ ] **Step 5: Provide `OcppService` in DI**

In `src/main/kotlin/di/AppModule.kt` add imports:

```kotlin
import io.konektis.ocpp.OcppService
import io.konektis.ocpp.db.ChargePointStore
import io.konektis.ocpp.db.ChargerSettingsStore
import io.konektis.ocpp.db.IdTagStore
import io.konektis.ocpp.db.TransactionStore
```

Add the provider:

```kotlin
    @ApplicationScope
    @Provides
    fun provideOcppService(config: Config, database: Database): OcppService =
        OcppService(
            ChargePointStore(database), IdTagStore(database),
            ChargerSettingsStore(database), TransactionStore(database), config.ocpp,
        ).also { it.initStores() }
```

In `src/main/kotlin/di/AppComponent.kt` add the import `import io.konektis.ocpp.OcppService` and abstract member:

```kotlin
    abstract val ocppService: OcppService
```

- [ ] **Step 6: Wire the service through `Application.module` and use the single SQLite DB**

In `src/main/kotlin/Application.kt`, change the `module(...)` call and signature to thread the service and the injected `Database`. Update the launch block (around line 82-86):

```kotlin
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(energyManager, config.websocket, dataCollector.statusStateFlow, component.ocppService, component.database)
                }
                server.start(wait = true)
            }
```

And update the `module` function (line 92). Add the import `import org.jetbrains.exposed.sql.Database`:

```kotlin
fun Application.module(
    energyManager: EnergyManager,
    wsConfig: WebSocketConfig,
    statusFlow: Flow<StatusState?>,
    ocppService: io.konektis.ocpp.OcppService,
    database: Database,
) {
    configureSecurity()
    configureAdministration()
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer(ocppService)
    configureDatabases(database)
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
```

Then in `src/main/kotlin/Databases.kt`, replace the H2 in-memory connection with the injected SQLite database (drop the in-line `Database.connect(...)`):

```kotlin
fun Application.configureDatabases(database: Database) {
    val userService = UserService(database)
```

(Leave the rest of `configureDatabases` — the `/users` routes — unchanged. Add the import `import org.jetbrains.exposed.sql.Database` if not already present.)

- [ ] **Step 7: Update `OcppServerTest.kt` for the injected service + subprotocol**

Replace the top of `src/test/kotlin/ocpp/OcppServerTest.kt` (the `configureTestOcppServer` helper and client setup). Replace the helper with one that builds a service against an in-memory DB and requests the subprotocol on the client:

```kotlin
import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.jetbrains.exposed.sql.Database

private fun Application.configureTestOcppServer() {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    val db = io.konektis.ocpp.freshTestDb()
    val cfg = OcppConfig(enabled = true, heartbeatInterval = 300, connectionTimeout = 60,
        callTimeoutSeconds = 1, autoProbeOnBoot = false)
    val service = OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db), cfg)
        .also { it.initStores() }
    configureOcppServer(service)
}
```

In every `client.webSocket("/ocpp/...") { ... }` call in this file, add the subprotocol request header by changing them to the form:

```kotlin
        client.webSocket("/ocpp/TEST001", request = { header(HttpHeaders.SecWebSocketProtocol, "ocpp1.6") }) {
```

(Apply the same `request = { header(HttpHeaders.SecWebSocketProtocol, "ocpp1.6") }` argument to each `client.webSocket(...)` in the file.)

- [ ] **Step 8: Delete the obsolete session-manager file and its test**

```bash
git rm src/main/kotlin/ocpp/OcppSessionManager.kt src/test/kotlin/ocpp/OcppSessionManagerTest.kt
```

- [ ] **Step 9: Build and run the OCPP tests**

Run: `./gradlew test --tests "io.konektis.ocpp.*"`
Expected: PASS — `OcppServiceTest` (4) and the updated `OcppServerTest` all green.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(ocpp): OcppService with SQLite persistence, live state flow, DI wiring, subprotocol negotiation"
```

---

### Task 4: Outbound request/response correlation

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Test: `src/test/kotlin/ocpp/OcppCorrelationTest.kt`

- [ ] **Step 1: Write the failing correlation test**

Create `src/test/kotlin/ocpp/OcppCorrelationTest.kt`:

```kotlin
package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OcppCorrelationTest {

    private fun newService(timeoutSec: Int = 1): OcppService {
        val db = freshTestDb()
        val cfg = OcppConfig(true, 300, 60, callTimeoutSeconds = timeoutSec)
        return OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db), cfg)
            .also { it.initStores() }
    }

    @Test
    fun callResolvesWhenChargerReplies() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        val session = mockk<DefaultWebSocketSession>(relaxed = true)
        coEvery { session.send(any()) } answers { sent.add(firstArg()) }
        svc.registerSession("CP1", session)

        val deferred = async { svc.sendCall("CP1", Action.Reset, buildJsonObject { put("type", "Soft") }) }
        runCurrent() // let the async coroutine run far enough to send the CALL frame

        val sentArray = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        val uniqueId = sentArray[1].jsonPrimitive.content
        svc.completeCall(uniqueId, buildJsonObject { put("status", "Accepted") })

        val result = deferred.await()
        assertEquals("Accepted", result?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun callReturnsNullOnTimeout() = runTest {
        val svc = newService(timeoutSec = 1)
        svc.registerSession("CP2", mockk(relaxed = true))
        val result = svc.sendCall("CP2", Action.Reset, buildJsonObject { put("type", "Soft") })
        assertNull(result)
    }

    @Test
    fun callReturnsNullForUnknownChargePoint() = runTest {
        val svc = newService()
        assertNull(svc.sendCall("NOPE", Action.Reset, buildJsonObject { put("type", "Soft") }))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCorrelationTest"`
Expected: FAIL — `sendCall` and real `completeCall`/`failCall` don't exist.

- [ ] **Step 3: Implement correlation in `OcppService`**

In `src/main/kotlin/ocpp/OcppService.kt`, add imports:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
```

Add a pending map field next to `sessions`:

```kotlin
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
```

Replace the temporary `completeCall`/`failCall` stubs with the real implementation, and add `sendCall`:

```kotlin
    /** Send a CALL to a charge point and await its CALL_RESULT payload, or null on timeout/error/unknown. */
    suspend fun sendCall(chargePointId: String, action: Action, payload: JsonObject): JsonObject? {
        val session = sessions[chargePointId] ?: run {
            logger.warn("sendCall: no session for {cp}", chargePointId); return null
        }
        val uniqueId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[uniqueId] = deferred
        val frame = buildJsonArray {
            add(MessageType.CALL.value); add(uniqueId); add(action.name); add(payload)
        }.toString()
        return try {
            session.session.send(Frame.Text(frame))
            logger.info("Sent {action} to {cp}", action.name, chargePointId)
            withTimeoutOrNull(config.callTimeoutSeconds.seconds) { deferred.await() }
                ?: run { logger.warn("{action} to {cp} timed out", action.name, chargePointId); null }
        } catch (e: Exception) {
            logger.error("sendCall {action} to {cp} failed: {err}", action.name, chargePointId, e.message)
            null
        } finally {
            pending.remove(uniqueId)
        }
    }

    fun completeCall(uniqueId: String, payload: JsonObject) {
        pending[uniqueId]?.complete(payload)
    }

    fun failCall(uniqueId: String, reason: String) {
        pending[uniqueId]?.completeExceptionally(RuntimeException(reason))
    }
```

- [ ] **Step 4: Run the correlation tests — should pass**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCorrelationTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ocpp/OcppService.kt src/test/kotlin/ocpp/OcppCorrelationTest.kt
git commit -m "feat(ocpp): request/response correlation for outbound CALLs with timeout"
```

---

### Task 5: Outbound commands (SetChargingProfile, GetConfiguration, RemoteStart/Stop, Reset, TriggerMessage)

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Test: `src/test/kotlin/ocpp/OcppCommandsTest.kt`

- [ ] **Step 1: Write the failing commands test**

Create `src/test/kotlin/ocpp/OcppCommandsTest.kt`:

```kotlin
package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class OcppCommandsTest {

    private fun newService(): OcppService {
        val db = freshTestDb()
        return OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, callTimeoutSeconds = 1)).also { it.initStores() }
    }

    private fun capturingSession(into: MutableList<Frame>): DefaultWebSocketSession =
        mockk<DefaultWebSocketSession>(relaxed = true).also { coEvery { it.send(any()) } answers { into.add(firstArg()) } }

    @Test
    fun setChargingProfileSendsProfileAndReturnsTrueOnAccepted() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.setChargingProfile("CP1", connectorId = 1, limit = 16.0, unit = ChargingRateUnitType.A) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("SetChargingProfile", arr[2].jsonPrimitive.content)
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }

    @Test
    fun resetSendsResetCall() = runTest {
        val svc = newService()
        val sent = mutableListOf<Frame>()
        svc.registerSession("CP1", capturingSession(sent))

        val job = async { svc.reset("CP1", ResetType.Soft) }
        runCurrent()
        val arr = Json.parseToJsonElement((sent.single() as Frame.Text).readText()).jsonArray
        assertEquals("Reset", arr[2].jsonPrimitive.content)
        svc.completeCall(arr[1].jsonPrimitive.content, buildJsonObject { put("status", "Accepted") })
        assertTrue(job.await())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCommandsTest"`
Expected: FAIL — `setChargingProfile`/`reset` don't exist.

- [ ] **Step 3: Implement the outbound command helpers**

In `src/main/kotlin/ocpp/OcppService.kt`, add these methods (they build on `sendCall` from Task 4). `acceptedStatus` reads the `status` field of the reply:

```kotlin
    private fun JsonObject?.isAccepted(): Boolean =
        this?.get("status")?.jsonPrimitive?.content == "Accepted"

    /** Apply a charging limit to a connector. limit is in the given unit (A or W). Returns true if Accepted. */
    suspend fun setChargingProfile(chargePointId: String, connectorId: Int, limit: Double, unit: ChargingRateUnitType): Boolean {
        val profile = ChargingProfile(
            chargingProfileId = 1,
            stackLevel = 0,
            chargingProfilePurpose = ChargingProfilePurposeType.TxDefaultProfile,
            chargingProfileKind = ChargingProfileKindType.Absolute,
            chargingSchedule = ChargingSchedule(
                chargingRateUnit = unit,
                chargingSchedulePeriod = listOf(ChargingSchedulePeriod(startPeriod = 0, limit = limit)),
            ),
        )
        val payload = json.encodeToJsonElement(SetChargingProfileRequest(connectorId, profile)).jsonObject
        return sendCall(chargePointId, Action.SetChargingProfile, payload).isAccepted()
    }

    suspend fun getConfiguration(chargePointId: String, keys: List<String>? = null): GetConfigurationResponse? {
        val payload = json.encodeToJsonElement(GetConfigurationRequest(keys)).jsonObject
        val reply = sendCall(chargePointId, Action.GetConfiguration, payload) ?: return null
        return runCatching { json.decodeFromJsonElement<GetConfigurationResponse>(reply) }.getOrNull()
    }

    suspend fun remoteStart(chargePointId: String, idTag: String, connectorId: Int? = null): Boolean {
        val payload = json.encodeToJsonElement(RemoteStartTransactionRequest(connectorId, idTag)).jsonObject
        return sendCall(chargePointId, Action.RemoteStartTransaction, payload).isAccepted()
    }

    suspend fun remoteStop(chargePointId: String, transactionId: Int): Boolean {
        val payload = json.encodeToJsonElement(RemoteStopTransactionRequest(transactionId)).jsonObject
        return sendCall(chargePointId, Action.RemoteStopTransaction, payload).isAccepted()
    }

    suspend fun reset(chargePointId: String, type: ResetType): Boolean {
        val payload = json.encodeToJsonElement(ResetRequest(type)).jsonObject
        return sendCall(chargePointId, Action.Reset, payload).isAccepted()
    }

    suspend fun triggerMessage(chargePointId: String, requestedMessage: String, connectorId: Int? = null): Boolean {
        val payload = buildJsonObject {
            put("requestedMessage", requestedMessage)
            if (connectorId != null) put("connectorId", connectorId)
        }
        return sendCall(chargePointId, Action.TriggerMessage, payload).isAccepted()
    }
```

- [ ] **Step 4: Run the commands tests — should pass**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCommandsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ocpp/OcppService.kt src/test/kotlin/ocpp/OcppCommandsTest.kt
git commit -m "feat(ocpp): outbound SetChargingProfile, GetConfiguration, RemoteStart/Stop, Reset, TriggerMessage"
```

---

### Task 6: Capability detection

Detect SmartCharging support (via GetConfiguration on boot) and power readability (when `Power.Active.Import` first arrives), persist both, and reflect in live state.

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Test: `src/test/kotlin/ocpp/OcppCapabilityTest.kt`

- [ ] **Step 1: Write the failing capability test**

Create `src/test/kotlin/ocpp/OcppCapabilityTest.kt`:

```kotlin
package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import kotlin.test.*

class OcppCapabilityTest {

    private fun newService(): Pair<OcppService, ChargePointStore> {
        val db = freshTestDb()
        val store = ChargePointStore(db)
        val svc = OcppService(store, IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, callTimeoutSeconds = 1, autoProbeOnBoot = false)).also { it.initStores() }
        return svc to store
    }

    @Test
    fun powerImportFlagSetFromMeterValues() = runTest {
        val (svc, store) = newService()
        svc.registerSession("CP1", mockk(relaxed = true))
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1"))

        svc.handleMeterValues("CP1", MeterValuesRequest(
            connectorId = 1,
            meterValue = listOf(MeterValue(
                timestamp = "2026-01-01T00:00:00Z",
                sampledValue = listOf(SampledValue(value = "2300", measurand = Measurand.`Power.Active.Import`, unit = UnitOfMeasure.W)),
            )),
        ))

        assertEquals(2300, svc.latestPowerW("CP1", 1))
        assertTrue(store.get("CP1")!!.powerImportSeen)
        assertTrue(svc.stateFlow.value.chargePoints.single().powerReadable)
    }

    @Test
    fun smartChargingDetectedFromGetConfiguration() = runTest {
        val (svc, store) = newService()
        svc.registerSession("CP1", mockk(relaxed = true))
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1")) // create the DB record
        // Simulate a GetConfiguration reply advertising SmartCharging.
        val resp = GetConfigurationResponse(
            configurationKey = listOf(ConfigurationKey("SupportedFeatureProfiles", true, "Core,SmartCharging")),
        )
        svc.applyCapabilityProbe("CP1", resp)

        assertTrue(store.get("CP1")!!.smartChargingSupported)
        assertTrue(svc.stateFlow.value.chargePoints.single().smartChargingSupported)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCapabilityTest"`
Expected: FAIL — `applyCapabilityProbe` doesn't exist and power flag isn't persisted.

- [ ] **Step 3: Persist the power flag in `handleMeterValues`**

In `src/main/kotlin/ocpp/OcppService.kt`, change `handleMeterValues` so it sets `powerImportSeen` the first time a power sample arrives:

```kotlin
    suspend fun handleMeterValues(chargePointId: String, request: MeterValuesRequest): MeterValuesResponse {
        val powerW = extractActivePowerW(request)
        if (powerW != null) {
            val s = sessions[chargePointId]
            s?.connectors?.getOrPut(request.connectorId) { ConnectorState(request.connectorId) }?.lastPowerW = powerW
            if (s != null && !s.powerImportSeen) {
                s.powerImportSeen = true
                chargePoints.setCapabilities(chargePointId, smartCharging = s.smartChargingSupported, powerImport = true)
            }
            recomputeState()
        }
        return MeterValuesResponse()
    }
```

- [ ] **Step 4: Add `applyCapabilityProbe` and a boot-time probe launcher**

Add a coroutine scope field near the top of `OcppService`:

```kotlin
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
```

Add the probe methods:

```kotlin
    /** Update SmartCharging support from a GetConfiguration reply (SupportedFeatureProfiles). */
    suspend fun applyCapabilityProbe(chargePointId: String, response: GetConfigurationResponse) {
        val profiles = response.configurationKey
            ?.firstOrNull { it.key == "SupportedFeatureProfiles" }?.value ?: ""
        val smartCharging = profiles.contains("SmartCharging", ignoreCase = true)
        val s = sessions[chargePointId]
        s?.smartChargingSupported = smartCharging
        chargePoints.setCapabilities(chargePointId, smartCharging = smartCharging, powerImport = s?.powerImportSeen ?: false)
        recomputeState()
    }

    /** Probe SmartCharging support in the background after boot. */
    fun probeCapabilities(chargePointId: String) {
        scope.launch {
            val resp = getConfiguration(chargePointId, listOf("SupportedFeatureProfiles"))
            if (resp != null) applyCapabilityProbe(chargePointId, resp)
        }
    }
```

Add the import `import kotlinx.coroutines.launch` at the top.

- [ ] **Step 5: Kick off the probe at the end of `handleBootNotification`**

In `handleBootNotification`, just before `return BootNotificationResponse(...)`, add:

```kotlin
        if (status == RegistrationStatus.Accepted && config.autoProbeOnBoot) probeCapabilities(chargePointId)
```

> The `autoProbeOnBoot` gate exists so `OcppServerTest` (which exercises a real WebSocket
> round-trip) can disable the async probe; otherwise the probe's `GetConfiguration` CALL could
> race ahead of the boot `CALL_RESULT` and break the client's first `incoming.receive()`.

- [ ] **Step 6: Run the capability tests — should pass**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppCapabilityTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Run the whole OCPP suite to catch regressions**

Run: `./gradlew test --tests "io.konektis.ocpp.*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/ocpp/OcppService.kt src/test/kotlin/ocpp/OcppCapabilityTest.kt
git commit -m "feat(ocpp): runtime capability detection (SmartCharging + power readability)"
```

---

## Phase 2 — EMS integration

### Task 7: `ChargerType.OCPP` config + `OcppCharger` + World wiring

**Files:**
- Modify: `src/main/kotlin/config/Config.kt`
- Create: `src/main/kotlin/devices/charger/OcppCharger.kt`
- Modify: `src/main/kotlin/devices/World.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`
- Test: `src/test/kotlin/devices/charger/OcppChargerTest.kt`

- [ ] **Step 1: Add `OCPP` charger type and OCPP fields to the `Charger` config**

In `src/main/kotlin/config/Config.kt`, replace the `ChargerType` enum and `Charger` data class:

```kotlin
@Serializable
enum class ChargerType {
    WebastoUnite,
    OCPP,
}

@Serializable
data class Charger(
    val type: ChargerType,
    val name: String,
    val host: String? = null,
    val chargingCurrent: ChargingCurrent,
    val chargePointId: String? = null,
    val connectorId: Int = 1,
)
```

> **Why this field order:** `host` and `chargingCurrent` keep their original positions so the
> existing positional call in `EnergyManagerTest.config()` —
> `ChargerConfig(ChargerType.WebastoUnite, "c", "h", ChargingCurrent(6.0, 32.0))` — still compiles
> unchanged. The new OCPP fields are appended with defaults. (Kotlin permits a non-default
> parameter after a defaulted one; you just can't omit `chargingCurrent`.)

- [ ] **Step 2: Write the failing `OcppCharger` test**

Create `src/test/kotlin/devices/charger/OcppChargerTest.kt`:

```kotlin
package io.konektis.devices.charger

import io.konektis.devices.Watt
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.konektis.ocpp.db.ChargerSettingsRecord
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppChargerTest {

    @Test
    fun getStateReturnsNullUntilPowerSeen() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns null
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertNull(charger.getState())
    }

    @Test
    fun getStateReflectsLatestPower() = runTest {
        val svc = mockk<OcppService>()
        every { svc.latestPowerW("CP1", 1) } returns 2300
        val charger = OcppCharger("CP1", 1, svc)
        charger.update()
        assertEquals(2300, charger.getState()?.update?.currentPower?.value)
    }

    @Test
    fun setMaxPowerSendsChargingProfileWhenCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns null
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680)) // 16A * 230V

        // 3680W / 230 = 16A, sent as amps
        coVerify { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }

    @Test
    fun setMaxPowerNoOpWhenNotCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns false
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680))

        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun setMaxPowerNoOpWhenEmsAutoControlDisabled() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns ChargerSettingsRecord("CP1", maxCurrentA = 32, emsAutoControl = false)
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680))

        coVerify(exactly = 0) { svc.setChargingProfile(any(), any(), any(), any()) }
    }

    @Test
    fun setMaxPowerClampsToConfiguredMaxCurrent() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.getChargerSettings("CP1") } returns ChargerSettingsRecord("CP1", maxCurrentA = 10, emsAutoControl = true)
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680)) // would be 16A, clamped to 10A

        coVerify { svc.setChargingProfile("CP1", 1, 10.0, ChargingRateUnitType.A) }
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.devices.charger.OcppChargerTest"`
Expected: FAIL — `OcppCharger` and `OcppService.isPowerControlCapable` don't exist.

- [ ] **Step 4: Add `isPowerControlCapable` to `OcppService`**

In `src/main/kotlin/ocpp/OcppService.kt`:

```kotlin
    /** True when the charge point is connected and advertised SmartCharging support. */
    fun isPowerControlCapable(chargePointId: String): Boolean =
        sessions[chargePointId]?.smartChargingSupported == true
```

- [ ] **Step 5: Implement `OcppCharger`**

Create `src/main/kotlin/devices/charger/OcppCharger.kt`:

```kotlin
package io.konektis.devices.charger

import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService

/**
 * A car charger reached over OCPP 1.6J. Power is read from MeterValues (pushed by the charger)
 * and throttled via SetChargingProfile. When the charge point does not support SmartCharging,
 * setMaxChargerPower is a logged no-op (power is then handled elsewhere, e.g. a Webasto entry).
 */
class OcppCharger(
    private val chargePointId: String,
    private val connectorId: Int,
    private val service: OcppService,
) : Charger, Klogging {

    override suspend fun update() {
        // No polling: the charger pushes MeterValues. update() is a no-op so getState() stays cheap.
    }

    override suspend fun getState(): DeviceUpdate<ChargerState>? {
        val powerW = service.latestPowerW(chargePointId, connectorId) ?: return null
        return DeviceUpdate(GlobalTimeSource.source.markNow(), ChargerState(Watt(powerW)))
    }

    override suspend fun setMaxChargerPower(power: Watt) {
        if (!service.isPowerControlCapable(chargePointId)) {
            logger.debug { "OcppCharger $chargePointId: SmartCharging unsupported, skipping setMaxChargerPower" }
            return
        }
        val settings = service.getChargerSettings(chargePointId)
        if (settings != null && !settings.emsAutoControl) {
            logger.debug { "OcppCharger $chargePointId: EMS auto-control disabled in settings, skipping" }
            return
        }
        // Send in amps for broadest charger compatibility (same 230V convention as Webasto),
        // clamped to the configured max current when settings exist.
        var amps = power.value / 230
        if (settings != null && amps > settings.maxCurrentA) amps = settings.maxCurrentA
        val ok = service.setChargingProfile(chargePointId, connectorId, amps.toDouble(), ChargingRateUnitType.A)
        if (!ok) logger.warn { "OcppCharger $chargePointId: SetChargingProfile($amps A) not accepted" }
    }
}
```

- [ ] **Step 6: Wire `OcppCharger` into `World.fromConfig`**

In `src/main/kotlin/devices/World.kt`, add the imports:

```kotlin
import io.konektis.devices.charger.OcppCharger
import io.konektis.ocpp.OcppService
```

Change the signature and the charger branch:

```kotlin
        fun fromConfig(config: Config, ocppService: OcppService): World {
```

```kotlin
            val chargers = config.devices.charger.associate {
                when (it.type) {
                    ChargerType.WebastoUnite -> Pair(it.name, Webasto(it.host!!))
                    ChargerType.OCPP -> Pair(it.name, OcppCharger(it.chargePointId!!, it.connectorId, ocppService))
                }
            }
```

- [ ] **Step 7: Update `provideWorld` in DI to pass the service**

In `src/main/kotlin/di/AppModule.kt`:

```kotlin
    @ApplicationScope
    @Provides
    fun provideWorld(config: Config, ocppService: OcppService): World = World.fromConfig(config, ocppService)
```

- [ ] **Step 8: Run the charger tests — should pass**

Run: `./gradlew test --tests "io.konektis.devices.charger.OcppChargerTest"`
Expected: PASS (4 tests).

- [ ] **Step 9: Full build (DI graph + everything compiles)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/config/Config.kt src/main/kotlin/devices/charger/OcppCharger.kt \
  src/main/kotlin/devices/World.kt src/main/kotlin/di/AppModule.kt src/main/kotlin/ocpp/OcppService.kt \
  src/test/kotlin/devices/charger/OcppChargerTest.kt
git commit -m "feat(ocpp): OcppCharger implementing Charger, wired into World and EMS via config"
```

---

### Task 8: End-to-end EMS control test through `OcppCharger`

Proves `SurplusPriorityStrategy` → `EnergyManager.applyDecisions` → `OcppCharger.setMaxChargerPower` reaches `OcppService.setChargingProfile`.

**Files:**
- Test: `src/test/kotlin/ems/OcppEmsIntegrationTest.kt`

- [ ] **Step 1: Inspect `EnergyManagerTest` for the existing harness pattern**

Run: `sed -n '1,60p' src/test/kotlin/ems/EnergyManagerTest.kt`
Expected: shows how `World`, `Config`, and `EnergyManager.tick()` are assembled in tests (mock devices, build `World`, call `tick()`).

- [ ] **Step 2: Write the integration test**

Create `src/test/kotlin/ems/OcppEmsIntegrationTest.kt`. Build a full Tier-1 `World` (grid exporting 4 kW, battery + heatpump present, an `OcppCharger` as the charger backed by a mocked `OcppService`), drive one `tick()`, and assert a charging profile was sent. The device-mock shapes mirror the private helpers in `EnergyManagerTest` (which can't be imported across files, so they're inlined here):

```kotlin
package io.konektis.ems

import io.konektis.GlobalTimeSource
import io.konektis.config.loadConfig
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.battery.BatteryState
import io.konektis.devices.charger.OcppCharger
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import io.konektis.ocpp.ChargingRateUnitType
import io.konektis.ocpp.OcppService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OcppEmsIntegrationTest {

    @Test
    fun surplusDrivesSetChargingProfileOnOcppCharger() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        every { svc.latestPowerW("CP1", 1) } returns 0   // charger currently drawing 0 W
        coEvery { svc.getChargerSettings("CP1") } returns null
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        val grid = mockk<Grid>(relaxed = true)
        coEvery { grid.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), GridState(Watt(-4000), Volt(230u))) // 4 kW export

        val battery = mockk<Battery>(relaxed = true)
        coEvery { battery.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), BatteryState(50u, Watt(0)))

        val heatpump = mockk<SmartConsumer>(relaxed = true)
        coEvery { heatpump.getState() } returns
            DeviceUpdate(GlobalTimeSource.source.markNow(), SmartConsumerState(Watt(0), ConsumeMode.Unrestricted))

        val world = World(
            grid = grid,
            chargers = mapOf("ocpp" to charger),
            solar = emptyMap(),
            smartConsumers = mapOf("hp" to heatpump),
            batteries = mapOf("bat" to battery),
        )
        // config.yaml's first charger entry bounds amps to [6, 32].
        val manager = EnergyManager(world, loadConfig("/config.yaml"), SurplusPriorityStrategy())

        manager.tick()

        // available = chargerP(0) + batteryP(0) - gridP(-4000) = 4000 W -> 4000/230 = 17 A (within [6,32]).
        coVerify { svc.setChargingProfile("CP1", 1, 17.0, ChargingRateUnitType.A) }
    }
}
```

- [ ] **Step 3: Run the integration test**

Run: `./gradlew test --tests "io.konektis.ems.OcppEmsIntegrationTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/ems/OcppEmsIntegrationTest.kt
git commit -m "test(ocpp): EMS surplus cascade drives SetChargingProfile through OcppCharger"
```

---

## Phase 3 — Local configuration & status webpage

### Task 9: `/ocpp-ui` page, live WebSocket, and REST API

**Files:**
- Create: `src/main/kotlin/ocpp/OcppWebUi.kt`
- Create: `src/main/resources/ocpp.html`
- Modify: `src/main/kotlin/Application.kt`
- Test: `src/test/kotlin/ocpp/OcppWebUiTest.kt`

- [ ] **Step 1: Add read/write helpers to `OcppService` for the UI**

The UI needs DB-backed lists and mutations. In `src/main/kotlin/ocpp/OcppService.kt` add:

```kotlin
    suspend fun listChargePoints() = chargePoints.all()
    suspend fun setChargePointAccepted(id: String, accepted: Boolean) = chargePoints.setAccepted(id, accepted)
    suspend fun listIdTags() = idTags.all()
    suspend fun putIdTag(idTag: String, status: String) = idTags.put(idTag, status)
    suspend fun deleteIdTag(idTag: String) = idTags.delete(idTag)
    suspend fun getChargerSettings(id: String) = settings.get(id)
    suspend fun putChargerSettings(id: String, maxCurrentA: Int, emsAutoControl: Boolean) =
        settings.put(id, maxCurrentA, emsAutoControl)
```

(`ChargePointStore.all()` and `IdTagStore.all()` already exist from Task 2.)

- [ ] **Step 2: Write the failing web UI test**

Create `src/test/kotlin/ocpp/OcppWebUiTest.kt`:

```kotlin
package io.konektis.ocpp

import io.konektis.config.OcppConfig
import io.konektis.ocpp.db.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class OcppWebUiTest {

    private fun Application.testModule(): OcppService {
        install(WebSockets) { pingPeriod = 30.seconds; timeout = 60.seconds }
        install(ContentNegotiation) { json() }
        val db = freshTestDb()
        val svc = OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, autoProbeOnBoot = false)).also { it.initStores() }
        configureOcppWebUi(svc)
        return svc
    }

    @Test
    fun servesThePage() = testApplication {
        application { testModule() }
        val resp = client.get("/ocpp-ui")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("OCPP"))
    }

    @Test
    fun idTagCrud() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule() }

        val post = client.post("/ocpp-ui/api/idtags") {
            contentType(ContentType.Application.Json)
            setBody("""{"idTag":"TAG1","status":"Accepted"}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)

        val list = client.get("/ocpp-ui/api/idtags").bodyAsText()
        assertTrue(list.contains("TAG1"))
    }

    @Test
    fun acceptChargePoint() = testApplication {
        lateinit var svc: OcppService
        application { svc = testModule() }
        svc.handleBootNotification("CP1", BootNotificationRequest("Acme", "X1")) // creates record (auto-accept on)

        val resp = client.post("/ocpp-ui/api/chargepoints/CP1/accepted") {
            contentType(ContentType.Application.Json)
            setBody("""{"accepted":false}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(svc.listChargePoints().single { it.chargePointId == "CP1" }.accepted)
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest"`
Expected: FAIL — `configureOcppWebUi` doesn't exist.

- [ ] **Step 4: Implement the web UI routes**

Create `src/main/kotlin/ocpp/OcppWebUi.kt`:

```kotlin
package io.konektis.ocpp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class IdTagBody(val idTag: String, val status: String)
@Serializable data class AcceptedBody(val accepted: Boolean)
@Serializable data class SettingsBody(val maxCurrentA: Int, val emsAutoControl: Boolean)
@Serializable data class StartBody(val idTag: String, val connectorId: Int? = null)
@Serializable data class StopBody(val transactionId: Int)
@Serializable data class ResetBody(val type: String = "Soft")

fun Application.configureOcppWebUi(service: OcppService) {
    val json = Json { encodeDefaults = true }
    routing {
        get("/ocpp-ui") {
            val bytes = object {}::class.java.getResourceAsStream("/ocpp.html")!!.readBytes()
            call.respondBytes(bytes, ContentType.Text.Html)
        }

        // Live status push.
        webSocket("/ocpp-ui/ws") {
            service.stateFlow.collect { send(Json.encodeToString(it)) }
        }

        route("/ocpp-ui/api") {
            get("/state") { call.respondText(Json.encodeToString(service.stateFlow.value), ContentType.Application.Json) }

            get("/chargepoints") { call.respondText(json.encodeToString(service.listChargePoints()), ContentType.Application.Json) }
            post("/chargepoints/{id}/accepted") {
                val id = call.parameters["id"]!!
                val body = call.receive<AcceptedBody>()
                service.setChargePointAccepted(id, body.accepted)
                call.respond(HttpStatusCode.OK)
            }

            get("/idtags") { call.respondText(json.encodeToString(service.listIdTags()), ContentType.Application.Json) }
            post("/idtags") {
                val body = call.receive<IdTagBody>()
                service.putIdTag(body.idTag, body.status)
                call.respond(HttpStatusCode.OK)
            }
            delete("/idtags/{idTag}") {
                service.deleteIdTag(call.parameters["idTag"]!!)
                call.respond(HttpStatusCode.OK)
            }

            get("/settings/{id}") {
                val s = service.getChargerSettings(call.parameters["id"]!!)
                if (s == null) call.respond(HttpStatusCode.NotFound)
                else call.respondText(json.encodeToString(s), ContentType.Application.Json)
            }
            post("/settings/{id}") {
                val body = call.receive<SettingsBody>()
                service.putChargerSettings(call.parameters["id"]!!, body.maxCurrentA, body.emsAutoControl)
                call.respond(HttpStatusCode.OK)
            }

            get("/transactions") {
                call.respondText(json.encodeToString(service.recentTransactions(50)), ContentType.Application.Json)
            }

            // Manual actions.
            post("/chargepoints/{id}/start") {
                val body = call.receive<StartBody>()
                val ok = service.remoteStart(call.parameters["id"]!!, body.idTag, body.connectorId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            post("/chargepoints/{id}/stop") {
                val body = call.receive<StopBody>()
                val ok = service.remoteStop(call.parameters["id"]!!, body.transactionId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            post("/chargepoints/{id}/reset") {
                val body = call.receive<ResetBody>()
                val type = runCatching { ResetType.valueOf(body.type) }.getOrDefault(ResetType.Soft)
                val ok = service.reset(call.parameters["id"]!!, type)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
        }
    }
}
```

- [ ] **Step 5: Create the HTML page**

Create `src/main/resources/ocpp.html` (vanilla, matches the dark theme of `status.html`):

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>OCPP Control</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      background: #111827; color: #e5e7eb; padding: 20px; max-width: 860px; margin: 0 auto; }
    h1 { color: #93c5fd; font-size: 1.2rem; margin-bottom: 16px; }
    h2 { color: #93c5fd; font-size: 1rem; margin: 20px 0 8px; }
    .card { background: #1f2937; border-radius: 10px; padding: 12px 14px; margin-bottom: 10px; }
    .row { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: center; }
    .muted { color: #9ca3af; font-size: 0.8rem; }
    .pill { font-size: 0.7rem; padding: 2px 8px; border-radius: 999px; background: #374151; }
    .pill.ok { background: #065f46; color: #d1fae5; }
    .pill.no { background: #7c2d12; color: #fed7aa; }
    button { background: #2563eb; color: white; border: 0; border-radius: 6px; padding: 6px 10px; cursor: pointer; font-size: 0.8rem; }
    button.secondary { background: #374151; }
    input { background: #111827; border: 1px solid #374151; color: #e5e7eb; border-radius: 6px; padding: 5px 8px; font-size: 0.8rem; }
    table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
    td, th { text-align: left; padding: 4px 6px; border-bottom: 1px solid #374151; }
    .reconnecting { background: #7c2d12; color: #fed7aa; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; display: none; }
  </style>
</head>
<body>
  <h1>OCPP Charger Control</h1>
  <div class="reconnecting" id="reconnecting">Reconnecting…</div>

  <h2>Connected charge points</h2>
  <div id="chargePoints"></div>

  <h2>Allow-list</h2>
  <div id="allowList"></div>

  <h2>Authorized idTags</h2>
  <div class="card">
    <div class="row">
      <input id="newTag" placeholder="idTag">
      <button onclick="addTag()">Add (Accepted)</button>
    </div>
    <div id="idTags" style="margin-top:8px"></div>
  </div>

  <h2>Recent sessions</h2>
  <div class="card"><table id="transactions"><tbody></tbody></table></div>

<script>
const $ = (id) => document.getElementById(id);

async function api(path, opts) {
  const r = await fetch('/ocpp-ui/api' + path, opts);
  return r;
}
async function getJson(path) { return (await api(path)).json(); }

function render(state) {
  const cp = $('chargePoints');
  cp.innerHTML = state.chargePoints.length ? '' : '<div class="muted">No charge points connected.</div>';
  state.chargePoints.forEach(c => {
    const conn = c.connectors.map(x =>
      `<div class="row"><span>Connector ${x.connectorId}: <b>${x.status}</b></span>
       <span class="muted">${x.powerW != null ? x.powerW + ' W' : '—'}${x.transactionId != null ? ' · tx ' + x.transactionId : ''}</span></div>`).join('');
    const div = document.createElement('div');
    div.className = 'card';
    div.innerHTML = `<div class="row"><b>${c.chargePointId}</b>
      <span><span class="pill ${c.smartChargingSupported?'ok':'no'}">SmartCharging</span>
      <span class="pill ${c.powerReadable?'ok':'no'}">Power read</span></span></div>
      <div class="muted">${c.vendor ?? ''} ${c.model ?? ''}</div>${conn}
      <div class="row" style="margin-top:8px">
        <span class="muted">Max A <input style="width:64px" id="maxA-${c.chargePointId}" type="number" min="0">
        <label><input id="auto-${c.chargePointId}" type="checkbox"> EMS auto</label></span>
        <button onclick="saveSettings('${c.chargePointId}')">Save</button>
      </div>
      <div class="row" style="margin-top:8px">
        <button class="secondary" onclick="startTx('${c.chargePointId}')">Start</button>
        <button class="secondary" onclick="resetCp('${c.chargePointId}')">Reset</button>
      </div>`;
    cp.appendChild(div);
    loadSettings(c.chargePointId);
  });
}

async function loadSettings(id) {
  const r = await api('/settings/' + id);
  const maxA = $('maxA-' + id), auto = $('auto-' + id);
  if (!maxA || !auto) return;
  if (r.ok) { const s = await r.json(); maxA.value = s.maxCurrentA; auto.checked = s.emsAutoControl; }
  else { maxA.value = 32; auto.checked = true; } // sensible defaults when none stored yet
}
async function saveSettings(id) {
  const maxCurrentA = parseInt($('maxA-' + id).value, 10) || 0;
  const emsAutoControl = $('auto-' + id).checked;
  await api('/settings/' + id, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({maxCurrentA, emsAutoControl}) });
}

async function refreshAllowList() {
  const list = await getJson('/chargepoints');
  const el = $('allowList');
  el.innerHTML = list.length ? '' : '<div class="muted">None known yet.</div>';
  list.forEach(c => {
    const div = document.createElement('div');
    div.className = 'card';
    div.innerHTML = `<div class="row"><span>${c.chargePointId} <span class="muted">${c.vendor ?? ''} ${c.model ?? ''}</span></span>
      <button onclick="setAccepted('${c.chargePointId}', ${!c.accepted})">${c.accepted ? 'Revoke' : 'Accept'}</button></div>`;
    el.appendChild(div);
  });
}

async function refreshTags() {
  const tags = await getJson('/idtags');
  const el = $('idTags');
  el.innerHTML = tags.length ? '' : '<div class="muted">No idTags.</div>';
  tags.forEach(t => {
    const div = document.createElement('div');
    div.className = 'row';
    div.innerHTML = `<span>${t.idTag} <span class="muted">(${t.status})</span></span>
      <button class="secondary" onclick="delTag('${t.idTag}')">Delete</button>`;
    el.appendChild(div);
  });
}

async function refreshTransactions() {
  const txs = await getJson('/transactions');
  const body = $('transactions').querySelector('tbody');
  body.innerHTML = '<tr><th>tx</th><th>cp</th><th>idTag</th><th>Wh</th></tr>' +
    txs.map(t => `<tr><td>${t.transactionId}</td><td>${t.chargePointId}</td><td>${t.idTag ?? ''}</td>
      <td>${t.meterStop != null ? (t.meterStop - t.meterStart) : '—'}</td></tr>`).join('');
}

async function setAccepted(id, accepted) {
  await api('/chargepoints/' + id + '/accepted', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({accepted}) });
  refreshAllowList();
}
async function addTag() {
  const idTag = $('newTag').value.trim(); if (!idTag) return;
  await api('/idtags', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({idTag, status:'Accepted'}) });
  $('newTag').value=''; refreshTags();
}
async function delTag(idTag) { await api('/idtags/' + encodeURIComponent(idTag), { method:'DELETE' }); refreshTags(); }
async function startTx(id) {
  const idTag = prompt('idTag to start with?'); if (!idTag) return;
  await api('/chargepoints/' + id + '/start', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({idTag}) });
}
async function resetCp(id) {
  await api('/chargepoints/' + id + '/reset', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({type:'Soft'}) });
}

function connect() {
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ocpp-ui/ws');
  ws.onmessage = (e) => { render(JSON.parse(e.data)); $('reconnecting').style.display='none'; };
  ws.onopen = () => { $('reconnecting').style.display='none'; refreshAllowList(); refreshTags(); refreshTransactions(); };
  ws.onclose = () => { $('reconnecting').style.display='block'; setTimeout(connect, 2000); };
}
connect();
setInterval(() => { refreshAllowList(); refreshTags(); refreshTransactions(); }, 10000);
</script>
</body>
</html>
```

- [ ] **Step 6: Register the web UI in `Application.module` and install JSON content negotiation**

The app currently installs `ContentNegotiation` nowhere, so the UI's `call.receive<…>()` JSON bodies need it. Install it once in `module` (no other code installs it, so there is no conflict), and register the web UI right after `configureOcppServer(ocppService)`.

Add imports to `src/main/kotlin/Application.kt`:

```kotlin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
```

In `module(...)`, add the install as the first line of the body and the web UI registration after the OCPP server:

```kotlin
    install(ContentNegotiation) { json() }
    configureSecurity()
    configureAdministration()
    configureSockets(energyManager, wsConfig)
    configureStatusPage(statusFlow)
    configureOcppServer(ocppService)
    io.konektis.ocpp.configureOcppWebUi(ocppService)
    configureDatabases(database)
    configureMonitoring()
    configureHTTP()
    configureRouting()
```

- [ ] **Step 7: Run the web UI tests — should pass**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest"`
Expected: PASS (3 tests).

- [ ] **Step 8: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/ocpp/OcppWebUi.kt src/main/resources/ocpp.html \
  src/main/kotlin/Application.kt src/main/kotlin/ocpp/OcppService.kt \
  src/test/kotlin/ocpp/OcppWebUiTest.kt
git commit -m "feat(ocpp): /ocpp-ui status + config webpage (live WS, allow-list, idTags, manual actions)"
```

---

## Final verification

- [ ] **Step 1: Run the entire test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all OCPP, config, EMS, charger, and existing tests pass.

- [ ] **Step 2: Manual smoke (optional, requires a charger or simulator)**

Add an OCPP charger to `config.yaml`:

```yaml
  charger:
    - type: OCPP
      name: Garage OCPP
      chargePointId: CP01
      connectorId: 1
      chargingCurrent:
        min: 6.0
        max: 32.0
```

Run `./gradlew run`, point an OCPP 1.6J charger (or simulator like `ocpp-cs` / SteVe test client) at `ws://<host>:8080/ocpp/CP01` with subprotocol `ocpp1.6`, then open `http://<host>:8080/ocpp-ui` to verify it appears, capability pills populate, MeterValues show power, and the EMS issues SetChargingProfile under surplus.

---

## Notes for the implementer

- **`encodeDefaults` and empty payloads:** the existing `dummy`-field pattern in `OcppMessages.kt` emits `"dummy":null`; chargers tolerate extra keys. Left as-is to avoid touching the working message model. If a strict charger rejects it, drop the `dummy` fields and the `encodeDefaults` won't emit anything for truly empty objects.
- **SQLite concurrency:** OCPP write volume is low; the single-writer model is fine. All store calls go through `dbQuery` on `Dispatchers.IO`.
- **Webasto retained:** nothing in this plan removes `Webasto`; configure either type per charger. For the "OCPP sessions + Webasto power" fallback, list both a `WebastoUnite` and an `OCPP` charger; the EMS uses `chargers.values.firstOrNull()` for power, so order config with the power-capable charger first.
- **Out of scope (later spec):** ClickHouse time-series meter history + charts; deriving power from `Energy.Active.Import.Register`; auth on webpage/endpoint; OCPP 2.0.1.
```
