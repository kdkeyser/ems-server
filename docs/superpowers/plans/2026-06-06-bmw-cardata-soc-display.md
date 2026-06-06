# BMW CarData car-SoC display — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream the BMW i5's battery State-of-Charge from BMW CarData over MQTT and surface it as a read-only percentage in the app.

**Architecture:** A new `CarDataService` (DI singleton, modelled on `OcppService`) owns an MQTT subscription to BMW's CarData broker and exposes the latest SoC as a `StateFlow<Int?>`. OAuth (device-code flow) and the refresh token persist in the existing SQLite DB. `EnergyManager` reads the SoC into `EMSState.carCharge` and re-exposes a `carSocFlow`; `Sockets.kt` pushes a new `CarStateUpdate` message to the app. Display only — no coupling into control logic.

**Tech Stack:** Kotlin, Ktor, kotlinx-serialization, Exposed/SQLite, kotlin-inject (DI), **ktor-mqtt** (`de.kempmobil.ktor.mqtt`, MQTT 5/TLS), klogging.

**Spec:** `docs/superpowers/specs/2026-06-06-bmw-cardata-soc-display-design.md`

---

## File structure

**Create:**
- `src/main/kotlin/cardata/CarDataConfig.kt` — config data class (also referenced from `config/Config.kt`).
- `src/main/kotlin/cardata/CarDataTokenStore.kt` — SQLite table + store for the refresh token/GCID.
- `src/main/kotlin/cardata/SocParser.kt` — pure `parseSoc(payload, descriptor)`.
- `src/main/kotlin/cardata/CarDataAuth.kt` — OAuth device-code + refresh (pure helpers + IO).
- `src/main/kotlin/cardata/CarDataMqttClient.kt` — thin ktor-mqtt wrapper (swappable).
- `src/main/kotlin/cardata/CarDataService.kt` — DI singleton tying it together; `socFlow`.
- `src/test/kotlin/cardata/SocParserTest.kt`
- `src/test/kotlin/cardata/CarDataAuthTest.kt`
- `src/test/kotlin/cardata/CarDataTokenStoreTest.kt`
- `src/test/kotlin/cardata/CarDataServiceTest.kt`
- `docs/adding-bmw-cardata.md` — operator setup (portal client_id, descriptor, bootstrap).

**Modify:**
- `gradle/libs.versions.toml`, `build.gradle.kts` — add ktor-mqtt.
- `src/main/kotlin/config/Config.kt` — add `cardata` to `Config` (defaulted/optional).
- `src/main/kotlin/ems/EMSState.kt` — add `carCharge: Int?`.
- `src/main/kotlin/ems/EnergyManager.kt` — read SoC; expose `carSocFlow`.
- `src/main/kotlin/Messages.kt` — add `CarStateUpdate`.
- `src/main/kotlin/Sockets.kt` — push `CarStateUpdate`.
- `src/main/kotlin/di/AppModule.kt`, `di/AppComponent.kt` — provide `CarDataService`.
- `src/main/kotlin/Application.kt` — start `CarDataService`.
- `src/main/resources/config.yaml` — example `cardata:` block (disabled).
- `src/test/kotlin/ems/...` and `src/test/kotlin/MessagesTest.kt` — wiring tests.

**External facts to confirm during bring-up (Task 11), not design decisions:** the BMW OAuth device-code/token endpoint URLs, the MQTT topic string for a VIN, and the exact JSON shape of a SoC message. These live in single clearly-marked constants and are reconciled against a captured real message.

---

## Task 1: Add ktor-mqtt dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts:29-62`

- [ ] **Step 1: Add the version + library entries**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
ktor-mqtt = "1.1.0"
```
Under `[libraries]` add:
```toml
ktor-mqtt-client = { module = "de.kempmobil.ktor.mqtt:mqtt-client", version.ref = "ktor-mqtt" }
ktor-mqtt-core = { module = "de.kempmobil.ktor.mqtt:mqtt-core", version.ref = "ktor-mqtt" }
```

- [ ] **Step 2: Add the implementation dependencies**

In `build.gradle.kts`, in the `dependencies { }` block (after `implementation(libs.modbus.tcp)` on line 50):
```kotlin
    implementation(libs.ktor.mqtt.client)
    implementation(libs.ktor.mqtt.core)
```

- [ ] **Step 3: Verify it resolves**

Run: `./gradlew dependencies --configuration runtimeClasspath > /dev/null && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (the artifact downloads from Maven Central, which is already configured).

- [ ] **Step 4: Commit**
```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build(cardata): add ktor-mqtt client dependency"
```

---

## Task 2: CarDataConfig + wire into Config

**Files:**
- Create: `src/main/kotlin/cardata/CarDataConfig.kt`
- Modify: `src/main/kotlin/config/Config.kt:88-96`
- Test: `src/test/kotlin/cardata/CarDataConfigTest.kt`

- [ ] **Step 1: Write the config data class**

Create `src/main/kotlin/cardata/CarDataConfig.kt`:
```kotlin
package io.konektis.cardata

import kotlinx.serialization.Serializable

/**
 * BMW CarData integration config. The whole block is optional and disabled by default so existing
 * deployments load unchanged. When [enabled], [clientId], [vin] and [socDescriptor] must be non-blank.
 */
@Serializable
data class CarDataConfig(
    val enabled: Boolean = false,
    val clientId: String = "",
    val vin: String = "",
    val socDescriptor: String = "",
    val brokerHost: String = "customer.streaming-cardata.bmwgroup.com",
    val brokerPort: Int = 9000,
) {
    fun validated(): CarDataConfig {
        if (enabled) {
            require(clientId.isNotBlank()) { "cardata.clientId must be set when cardata.enabled" }
            require(vin.isNotBlank()) { "cardata.vin must be set when cardata.enabled" }
            require(socDescriptor.isNotBlank()) { "cardata.socDescriptor must be set when cardata.enabled" }
        }
        return this
    }
}
```

- [ ] **Step 2: Wire it into the top-level Config**

In `src/main/kotlin/config/Config.kt`, add the import near the top (after line 7):
```kotlin
import io.konektis.cardata.CarDataConfig
```
Then add the field to `data class Config` (currently lines 89-96) — add `cardata` with a default before `refreshThreads`:
```kotlin
@Serializable
data class Config(
    val grid: Grid,
    val devices: Devices,
    val ocpp: OcppConfig,
    val websocket: WebSocketConfig = WebSocketConfig("user", "password"),
    val database: DatabaseConfig = DatabaseConfig(),
    val cardata: CarDataConfig = CarDataConfig(),
    val refreshThreads : Int = 50
)
```

- [ ] **Step 3: Write the failing test**

Create `src/test/kotlin/cardata/CarDataConfigTest.kt`:
```kotlin
package io.konektis.cardata

import kotlin.test.*

class CarDataConfigTest {
    @Test
    fun defaultIsDisabledAndValidates() {
        val c = CarDataConfig()
        assertFalse(c.enabled)
        assertEquals("customer.streaming-cardata.bmwgroup.com", c.brokerHost)
        assertEquals(9000, c.brokerPort)
        assertSame(c, c.validated()) // disabled: no required fields
    }

    @Test
    fun enabledWithoutClientIdFails() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CarDataConfig(enabled = true, vin = "WBA1", socDescriptor = "x").validated()
        }
        assertTrue(ex.message!!.contains("clientId"))
    }

    @Test
    fun enabledWithAllFieldsValidates() {
        val c = CarDataConfig(enabled = true, clientId = "cid", vin = "WBA1", socDescriptor = "x")
        assertSame(c, c.validated())
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataConfigTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/kotlin/cardata/CarDataConfig.kt src/main/kotlin/config/Config.kt src/test/kotlin/cardata/CarDataConfigTest.kt
git commit -m "feat(cardata): optional CarDataConfig block (disabled by default)"
```

---

## Task 3: CarDataTokenStore (SQLite)

**Files:**
- Create: `src/main/kotlin/cardata/CarDataTokenStore.kt`
- Test: `src/test/kotlin/cardata/CarDataTokenStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cardata/CarDataTokenStoreTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataTokenStoreTest'`
Expected: FAIL — unresolved references `SqlCarDataTokenStore`, `CarDataTokenRecord`.

- [ ] **Step 3: Implement the store**

Create `src/main/kotlin/cardata/CarDataTokenStore.kt`:
```kotlin
package io.konektis.cardata

import io.konektis.ocpp.db.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

object CarDataTokens : Table("cardata_tokens") {
    val clientId = varchar("client_id", 128)
    val refreshToken = varchar("refresh_token", 4096)
    val gcid = varchar("gcid", 128).nullable()
    override val primaryKey = PrimaryKey(clientId)
}

data class CarDataTokenRecord(val clientId: String, val refreshToken: String, val gcid: String?)

/** Persists the long-lived refresh token so the one-time device approval survives restarts.
 *  An interface so the service's tests can fake it. */
interface CarDataTokenStore {
    fun init()
    suspend fun get(clientId: String): CarDataTokenRecord?
    suspend fun save(clientId: String, refreshToken: String, gcid: String?)
}

class SqlCarDataTokenStore(private val db: Database) : CarDataTokenStore {
    override fun init() = transaction(db) { SchemaUtils.create(CarDataTokens) }

    override suspend fun get(clientId: String): CarDataTokenRecord? = dbQuery(db) {
        CarDataTokens.selectAll().where { CarDataTokens.clientId eq clientId }.singleOrNull()?.let {
            CarDataTokenRecord(it[CarDataTokens.clientId], it[CarDataTokens.refreshToken], it[CarDataTokens.gcid])
        }
    }

    override suspend fun save(clientId: String, refreshToken: String, gcid: String?) = dbQuery(db) {
        val exists = CarDataTokens.selectAll().where { CarDataTokens.clientId eq clientId }.any()
        if (exists) {
            CarDataTokens.update({ CarDataTokens.clientId eq clientId }) {
                it[CarDataTokens.refreshToken] = refreshToken
                it[CarDataTokens.gcid] = gcid
            }
        } else {
            CarDataTokens.insert {
                it[CarDataTokens.clientId] = clientId
                it[CarDataTokens.refreshToken] = refreshToken
                it[CarDataTokens.gcid] = gcid
            }
        }
        Unit
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataTokenStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/kotlin/cardata/CarDataTokenStore.kt src/test/kotlin/cardata/CarDataTokenStoreTest.kt
git commit -m "feat(cardata): SQLite refresh-token store"
```

---

## Task 4: SoC parser (pure)

**Files:**
- Create: `src/main/kotlin/cardata/SocParser.kt`
- Test: `src/test/kotlin/cardata/SocParserTest.kt`

> **Note on the payload shape:** CarData streaming messages are JSON of the form
> `{"vin":"…","data":{"<descriptor>":{"value":<v>,"timestamp":"…"}}}`. This is the assumed shape;
> Task 11 captures a real message and reconciles the sample + parser if BMW differs. The parser is
> deliberately tolerant (returns null rather than throwing) so a shape surprise degrades to "—", not a crash.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cardata/SocParserTest.kt`:
```kotlin
package io.konektis.cardata

import kotlin.test.*

class SocParserTest {
    private val desc = "vehicle.drivetrain.electricEngine.charging.soc"

    @Test
    fun extractsIntegerSoc() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":73,"timestamp":"2026-06-06T10:00:00Z"}}}"""
        assertEquals(73, parseSoc(payload, desc))
    }

    @Test
    fun roundsDecimalSoc() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":72.6,"timestamp":"t"}}}"""
        assertEquals(73, parseSoc(payload, desc))
    }

    @Test
    fun acceptsStringNumberValue() {
        val payload = """{"vin":"WBA1","data":{"$desc":{"value":"50","timestamp":"t"}}}"""
        assertEquals(50, parseSoc(payload, desc))
    }

    @Test
    fun returnsNullWhenDescriptorAbsent() {
        val payload = """{"vin":"WBA1","data":{"some.other.descriptor":{"value":10}}}"""
        assertNull(parseSoc(payload, desc))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(parseSoc("not json", desc))
        assertNull(parseSoc("""{"data":{"$desc":{"value":"NaN"}}}""", desc))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.cardata.SocParserTest'`
Expected: FAIL — unresolved reference `parseSoc`.

- [ ] **Step 3: Implement the parser**

Create `src/main/kotlin/cardata/SocParser.kt`:
```kotlin
package io.konektis.cardata

import kotlinx.serialization.json.*
import kotlin.math.roundToInt

private val json = Json { ignoreUnknownKeys = true }

/**
 * Extract the SoC percentage for [descriptor] from a CarData MQTT JSON [payload].
 * Returns null on any unexpected shape, a missing descriptor, or a non-numeric value
 * (tolerant by design — a surprise must not crash the subscriber).
 */
fun parseSoc(payload: String, descriptor: String): Int? = runCatching {
    val root = json.parseToJsonElement(payload).jsonObject
    val data = root["data"]?.jsonObject ?: return null
    val entry = data[descriptor] ?: return null
    // Value may be a bare number/string, or an object with a "value" field.
    val valueElement = (entry as? JsonObject)?.get("value") ?: entry
    val prim = valueElement.jsonPrimitive
    val number = prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull() ?: return null
    number.roundToInt()
}.getOrNull()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.cardata.SocParserTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/kotlin/cardata/SocParser.kt src/test/kotlin/cardata/SocParserTest.kt
git commit -m "feat(cardata): tolerant SoC payload parser"
```

---

## Task 5: CarDataAuth pure helpers

**Files:**
- Create: `src/main/kotlin/cardata/CarDataAuth.kt` (pure helpers now; IO added in Task 11)
- Test: `src/test/kotlin/cardata/CarDataAuthTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cardata/CarDataAuthTest.kt`:
```kotlin
package io.konektis.cardata

import java.time.Instant
import kotlin.test.*

class CarDataAuthTest {
    @Test
    fun parsesDeviceCodeResponse() {
        val r = parseDeviceCodeResponse(
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://x/verify","interval":5,"expires_in":600}"""
        )!!
        assertEquals("dc", r.deviceCode)
        assertEquals("ABCD-1234", r.userCode)
        assertEquals("https://x/verify", r.verificationUri)
        assertEquals(5, r.intervalSeconds)
    }

    @Test
    fun parsesTokenResponse() {
        val r = parseTokenResponse(
            """{"id_token":"idt","refresh_token":"rt","expires_in":3600,"gcid":"GCID-1"}"""
        )!!
        assertEquals("idt", r.idToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(3600, r.expiresInSeconds)
        assertEquals("GCID-1", r.gcid)
    }

    @Test
    fun parseTokenResponseReturnsNullOnError() {
        assertNull(parseTokenResponse("""{"error":"authorization_pending"}"""))
    }

    @Test
    fun needsRefreshHonoursMargin() {
        val now = Instant.parse("2026-06-06T10:00:00Z")
        val expiresSoon = now.plusSeconds(120)
        val expiresLater = now.plusSeconds(600)
        assertTrue(needsRefresh(expiresSoon, now, marginSeconds = 300))
        assertFalse(needsRefresh(expiresLater, now, marginSeconds = 300))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataAuthTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement the pure helpers**

Create `src/main/kotlin/cardata/CarDataAuth.kt`:
```kotlin
package io.konektis.cardata

import kotlinx.serialization.json.*
import java.time.Instant

data class DeviceCodeResponse(
    val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSeconds: Int,
)

data class TokenResponse(
    val idToken: String, val refreshToken: String?, val expiresInSeconds: Int, val gcid: String?,
)

private val authJson = Json { ignoreUnknownKeys = true }

fun parseDeviceCodeResponse(body: String): DeviceCodeResponse? = runCatching {
    val o = authJson.parseToJsonElement(body).jsonObject
    DeviceCodeResponse(
        deviceCode = o["device_code"]!!.jsonPrimitive.content,
        userCode = o["user_code"]!!.jsonPrimitive.content,
        verificationUri = (o["verification_uri_complete"] ?: o["verification_uri"])!!.jsonPrimitive.content,
        intervalSeconds = o["interval"]?.jsonPrimitive?.intOrNull ?: 5,
    )
}.getOrNull()

/** Parse a token endpoint success body. Returns null for error bodies (e.g. authorization_pending). */
fun parseTokenResponse(body: String): TokenResponse? = runCatching {
    val o = authJson.parseToJsonElement(body).jsonObject
    if (o["error"] != null) return null
    val idToken = o["id_token"]?.jsonPrimitive?.content ?: return null
    TokenResponse(
        idToken = idToken,
        refreshToken = o["refresh_token"]?.jsonPrimitive?.content,
        expiresInSeconds = o["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600,
        gcid = o["gcid"]?.jsonPrimitive?.content,
    )
}.getOrNull()

/** True when [expiresAt] is within [marginSeconds] of [now] (so we should refresh proactively). */
fun needsRefresh(expiresAt: Instant, now: Instant, marginSeconds: Long): Boolean =
    !expiresAt.minusSeconds(marginSeconds).isAfter(now)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataAuthTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/kotlin/cardata/CarDataAuth.kt src/test/kotlin/cardata/CarDataAuthTest.kt
git commit -m "feat(cardata): OAuth device-code/token response parsing + refresh-margin helpers"
```

---

## Task 6: CarDataService — SoC state + message handling

This task builds the service's **testable core**: holding the latest SoC and updating it from a raw MQTT
payload. The live MQTT/auth IO is added in Task 11; here `start()` is left as a stub that the wiring can
call safely even when disabled.

**Files:**
- Create: `src/main/kotlin/cardata/CarDataService.kt`
- Test: `src/test/kotlin/cardata/CarDataServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cardata/CarDataServiceTest.kt`:
```kotlin
package io.konektis.cardata

import io.mockk.mockk
import kotlin.test.*

class CarDataServiceTest {
    private fun service(descriptor: String = "soc.desc"): CarDataService {
        val cfg = CarDataConfig(enabled = true, clientId = "cid", vin = "WBA1", socDescriptor = descriptor)
        return CarDataService(cfg, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun socFlowStartsNull() {
        assertNull(service().socFlow.value)
    }

    @Test
    fun onMessageUpdatesSocFlowForConfiguredDescriptor() {
        val svc = service("soc.desc")
        svc.onMessage("""{"vin":"WBA1","data":{"soc.desc":{"value":64}}}""")
        assertEquals(64, svc.socFlow.value)
    }

    @Test
    fun onMessageIgnoresUnrelatedDescriptor() {
        val svc = service("soc.desc")
        svc.onMessage("""{"vin":"WBA1","data":{"soc.desc":{"value":40}}}""")
        svc.onMessage("""{"vin":"WBA1","data":{"other":{"value":99}}}""") // no soc -> keep last
        assertEquals(40, svc.socFlow.value)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataServiceTest'`
Expected: FAIL — `CarDataService` unresolved.

- [ ] **Step 3: Implement the service core**

Create `src/main/kotlin/cardata/CarDataService.kt`:
```kotlin
package io.konektis.cardata

import io.klogging.Klogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the BMW CarData connection and exposes the latest car State-of-Charge as [socFlow].
 * Modelled on OcppService: a DI singleton with its own lifecycle. Display-only; nothing here
 * feeds control logic. The MQTT/auth IO is wired in [start] (Task 11); [onMessage] is the pure,
 * unit-tested update path.
 */
class CarDataService(
    private val config: CarDataConfig,
    private val tokenStore: CarDataTokenStore,
    private val auth: CarDataAuth,
    private val mqtt: CarDataMqttClient,
) : Klogging {

    private val _socFlow = MutableStateFlow<Int?>(null)
    val socFlow: StateFlow<Int?> = _socFlow.asStateFlow()

    /** Parse a raw MQTT payload; update [socFlow] only when it yields a SoC. */
    fun onMessage(payload: String) {
        val soc = parseSoc(payload, config.socDescriptor) ?: return
        _socFlow.value = soc
    }

    /** Start the connection loop. No-op when disabled. Full IO added in Task 11. */
    suspend fun start() {
        if (!config.enabled) return
        // Task 11: bootstrap auth, then mqtt.run { onMessage(it) } with token-aware reconnect.
    }
}
```

> Note: this references `CarDataAuth` (a class) and `CarDataMqttClient`. Task 5 defined only top-level
> helper functions in `CarDataAuth.kt`; add the **class** in Task 11. To compile **now**, create minimal
> stubs in the next step.

- [ ] **Step 4: Add minimal compile stubs for the IO collaborators**

Append to `src/main/kotlin/cardata/CarDataAuth.kt`:
```kotlin
/** OAuth client for CarData. Pure parsing lives in the top-level helpers above; IO added in Task 11. */
class CarDataAuth(
    private val config: CarDataConfig,
    private val tokenStore: CarDataTokenStore,
    private val http: HttpClient,
) {
    // Task 11: ensureAuthorized() (device-code bootstrap) + currentIdToken() (refresh).
}
```
Add the import to that file's header:
```kotlin
import io.ktor.client.HttpClient
```
Create `src/main/kotlin/cardata/CarDataMqttClient.kt`:
```kotlin
package io.konektis.cardata

/** Thin, swappable wrapper around the MQTT library. Real implementation in Task 11. */
class CarDataMqttClient(
    private val config: CarDataConfig,
    private val auth: CarDataAuth,
) {
    // Task 11: run(onMessage: (String) -> Unit) with TLS/MQTT5 connect + token-aware reconnect.
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.cardata.CarDataServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**
```bash
git add src/main/kotlin/cardata/CarDataService.kt src/main/kotlin/cardata/CarDataMqttClient.kt src/main/kotlin/cardata/CarDataAuth.kt src/test/kotlin/cardata/CarDataServiceTest.kt
git commit -m "feat(cardata): CarDataService SoC state + message handling (IO stubbed)"
```

---

## Task 7: EMSState.carCharge + EnergyManager wiring

**Files:**
- Modify: `src/main/kotlin/ems/EMSState.kt`
- Modify: `src/main/kotlin/ems/EnergyManager.kt:23-48, 37, 159-177`
- Test: `src/test/kotlin/ems/EnergyManagerCarChargeTest.kt`

- [ ] **Step 1: Add the field to EMSState**

In `src/main/kotlin/ems/EMSState.kt`, add `carCharge` (defaulted so existing positional constructions keep working):
```kotlin
data class EMSState(
    val gridPower : Int?,
    val gridVoltage : Int?,
    val chargerPower : Int?,
    val heatpumpPower : Int?,
    val solarPower: Int?,
    val batteryPower : Int?,
    val batteryCharge: Int?,
    val chargerConnection: ChargerConnection? = null,
    val carCharge: Int? = null,
)
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/ems/EnergyManagerCarChargeTest.kt`:
```kotlin
package io.konektis.ems

import io.konektis.cardata.CarDataService
import io.konektis.config.Config
import io.konektis.devices.World
import io.konektis.ocpp.db.ChargerControlStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EnergyManagerCarChargeTest {
    private fun manager(carService: CarDataService?): EnergyManager {
        val world = mockk<World>(relaxed = true)
        val config = mockk<Config>(relaxed = true)
        val strategy = mockk<Strategy>(relaxed = true)
        val store = mockk<ChargerControlStore>(relaxed = true)
        return EnergyManager(world, config, strategy, store, carService)
    }

    @Test
    fun buildEMSStateIncludesCarChargeFromService() = runTest {
        val svc = mockk<CarDataService>()
        every { svc.socFlow } returns MutableStateFlow<Int?>(58)
        assertEquals(58, manager(svc).buildEMSState().carCharge)
    }

    @Test
    fun buildEMSStateCarChargeNullWhenNoService() = runTest {
        assertNull(manager(null).buildEMSState().carCharge)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.ems.EnergyManagerCarChargeTest'`
Expected: FAIL — `EnergyManager` has no 5th constructor param.

- [ ] **Step 4: Wire the service into EnergyManager**

In `src/main/kotlin/ems/EnergyManager.kt`:

Add the import (near the other imports):
```kotlin
import io.konektis.cardata.CarDataService
import kotlinx.coroutines.flow.StateFlow
```
(`StateFlow` may already be imported — if so, don't duplicate.)

Add the constructor param (constructor currently ends at line 28):
```kotlin
class EnergyManager(
    private val world: World,
    private val config: Config,
    private val strategy: Strategy,
    private val chargerControlStore: ChargerControlStore,
    private val carDataService: CarDataService? = null,
) : Klogging {
```

Expose the SoC flow for the socket (add near `modeFlow`, around line 38). When there is no service, use a constant null flow:
```kotlin
    val carSocFlow: StateFlow<Int?> =
        carDataService?.socFlow ?: MutableStateFlow<Int?>(null)
```

In `buildEMSState()` (lines 159-177), set `carCharge` in the returned `EMSState`:
```kotlin
        return EMSState(
            gridPower = gridState?.power?.value,
            gridVoltage = gridState?.voltage?.value?.toInt(),
            chargerPower = chargerState?.currentPower?.value,
            heatpumpPower = heatpumpState?.power?.value,
            solarPower = solarPower,
            batteryPower = batteryState?.power?.value,
            batteryCharge = batteryState?.charge?.toInt(),
            chargerConnection = chargerState?.connection,
            carCharge = carDataService?.socFlow?.value,
        )
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.ems.EnergyManagerCarChargeTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full suite (the EnergyManager constructor changed)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The new param is defaulted, so existing `EnergyManager(...)` call sites and tests still compile.

- [ ] **Step 7: Commit**
```bash
git add src/main/kotlin/ems/EMSState.kt src/main/kotlin/ems/EnergyManager.kt src/test/kotlin/ems/EnergyManagerCarChargeTest.kt
git commit -m "feat(cardata): surface car SoC in EMSState + EnergyManager.carSocFlow"
```

---

## Task 8: CarStateUpdate message + socket push

**Files:**
- Modify: `src/main/kotlin/Messages.kt:40-52`
- Modify: `src/main/kotlin/Sockets.kt:56-73, 80-91`
- Test: `src/test/kotlin/MessagesTest.kt`

- [ ] **Step 1: Write the failing test**

In `src/test/kotlin/MessagesTest.kt`, add a serialization round-trip test (match the file's existing style; this asserts the `@SerialName` discriminator the app depends on):
```kotlin
    @Test
    fun carStateUpdateRoundTrips() {
        val msg: io.konektis.Message = io.konektis.Message.CarStateUpdate(73)
        val text = kotlinx.serialization.json.Json.encodeToString(msg)
        assertTrue(text.contains("\"CarStateUpdate\""))
        val back = io.konektis.deserializeMessage(text)
        assertEquals(io.konektis.Message.CarStateUpdate(73), back)
    }

    @Test
    fun carStateUpdateAllowsNullSoc() {
        val text = kotlinx.serialization.json.Json.encodeToString<io.konektis.Message>(io.konektis.Message.CarStateUpdate(null))
        assertEquals(io.konektis.Message.CarStateUpdate(null), io.konektis.deserializeMessage(text))
    }
```
(If `MessagesTest.kt` already imports `io.konektis.*` and `kotlin.test.*`, drop the fully-qualified prefixes to match.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'io.konektis.MessagesTest'`
Expected: FAIL — `Message.CarStateUpdate` unresolved.

- [ ] **Step 3: Add the message type**

In `src/main/kotlin/Messages.kt`, inside `sealed class Message` (after `ChargerControlUpdate`, line 51):
```kotlin
    @Serializable @SerialName("CarStateUpdate")
    data class CarStateUpdate(val soc: Int?) : Message()
```

- [ ] **Step 4: Run the message test to verify it passes**

Run: `./gradlew test --tests 'io.konektis.MessagesTest'`
Expected: PASS.

- [ ] **Step 5: Push it over the socket**

In `src/main/kotlin/Sockets.kt`, add a collector alongside `modeJob`/`chargingJob` (after line 73):
```kotlin
            val carJob = launch {
                energyManager.carSocFlow.collect { soc ->
                    if (authenticated) {
                        send(Json.encodeToString(Message.CarStateUpdate(soc) as Message))
                    }
                }
            }
```
And send the current value once on successful auth (after the `ChargerControlUpdate` send at line 86):
```kotlin
                                    send(Json.encodeToString(Message.CarStateUpdate(energyManager.carSocFlow.value) as Message))
```

- [ ] **Step 6: Verify the full suite still passes**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
git add src/main/kotlin/Messages.kt src/main/kotlin/Sockets.kt src/test/kotlin/MessagesTest.kt
git commit -m "feat(cardata): CarStateUpdate WS message + socket push (change-only + on auth)"
```

---

## Task 9: DI + Application wiring

**Files:**
- Modify: `src/main/kotlin/di/AppModule.kt:54-74`
- Modify: `src/main/kotlin/di/AppComponent.kt:18-23`
- Modify: `src/main/kotlin/Application.kt:77-92`

- [ ] **Step 1: Provide CarData collaborators in AppModule**

In `src/main/kotlin/di/AppModule.kt`, add imports:
```kotlin
import io.konektis.cardata.CarDataAuth
import io.konektis.cardata.CarDataMqttClient
import io.konektis.cardata.CarDataService
import io.konektis.cardata.CarDataTokenStore
import io.konektis.cardata.SqlCarDataTokenStore
```
Add provider methods (after `provideOcppService`, line 73):
```kotlin
    @ApplicationScope
    @Provides
    fun provideCarDataTokenStore(database: Database): CarDataTokenStore =
        SqlCarDataTokenStore(database).also { it.init() }

    @ApplicationScope
    @Provides
    fun provideCarDataService(
        config: Config, tokenStore: CarDataTokenStore, httpClient: HttpClient,
    ): CarDataService {
        val cfg = config.cardata.validated()
        val auth = CarDataAuth(cfg, tokenStore, httpClient)
        val mqtt = CarDataMqttClient(cfg, auth)
        return CarDataService(cfg, tokenStore, auth, mqtt)
    }
```
Update `provideEnergyManager` (lines 58-61) to take the service:
```kotlin
    @ApplicationScope
    @Provides
    fun provideEnergyManager(
        world: World, config: Config, strategy: Strategy,
        chargerControlStore: ChargerControlStore, carDataService: CarDataService,
    ): EnergyManager = EnergyManager(world, config, strategy, chargerControlStore, carDataService)
```

- [ ] **Step 2: Expose it on the component**

In `src/main/kotlin/di/AppComponent.kt`, add the import and an abstract accessor:
```kotlin
import io.konektis.cardata.CarDataService
```
```kotlin
    abstract val carDataService: CarDataService
```

- [ ] **Step 3: Start the service in Application**

In `src/main/kotlin/Application.kt`, inside the `coroutineScope { }` (after the `energyManager.run()` launch, line 85):
```kotlin
            launch { component.carDataService.start() }
```
(When `cardata.enabled` is false, `start()` returns immediately — see Task 6.)

- [ ] **Step 4: Verify it compiles and all tests pass**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — kotlin-inject (KSP) regenerates the component with the new bindings.

- [ ] **Step 5: Commit**
```bash
git add src/main/kotlin/di/AppModule.kt src/main/kotlin/di/AppComponent.kt src/main/kotlin/Application.kt
git commit -m "feat(cardata): wire CarDataService through DI and start it on boot"
```

---

## Task 10: Example config + operator docs

**Files:**
- Modify: `src/main/resources/config.yaml`
- Create: `docs/adding-bmw-cardata.md`

- [ ] **Step 1: Add a disabled example block to the bundled config**

Append to `src/main/resources/config.yaml` (keep the existing indentation/style of the file):
```yaml
cardata:
  enabled: false
  clientId: ""            # generated in the BMW CarData portal (CARDATA API)
  vin: ""                 # the vehicle to subscribe to
  socDescriptor: ""       # the SoC telemetry descriptor (confirm in the portal's descriptor list)
  # brokerHost / brokerPort default to BMW's customer streaming broker.
```

- [ ] **Step 2: Write the operator guide**

Create `docs/adding-bmw-cardata.md` documenting: (a) generate the `client_id` in the CarData portal,
(b) enable the SoC descriptor under "Configure data stream", (c) set `cardata.enabled: true` + fields,
(d) first start prints a verification URL + user code in `docker logs` — approve it once, (e) the
refresh token then persists in the SQLite volume; if it ever lapses the prompt reappears. Note the
one-MQTT-connection-per-account limit.

- [ ] **Step 3: Commit**
```bash
git add src/main/resources/config.yaml docs/adding-bmw-cardata.md
git commit -m "docs(cardata): example config block + operator setup guide"
```

---

## Task 11: Live IO — OAuth device-code flow + MQTT subscription

This is the integration layer that talks to BMW. It is **not** unit-tested (no live broker in CI);
it is verified by a manual bring-up at the end. Keep everything behind the existing class boundaries so
the swap is contained.

> **Before coding, gather three external facts from BMW's CarData API docs**
> (`https://bmw-cardata.bmwgroup.com/thirdparty/public/car-data/technical-configuration/api-documentation`):
> 1. the **device authorization** + **token** endpoint URLs,
> 2. the **MQTT topic** to subscribe to for a VIN,
> 3. confirm the broker is `customer.streaming-cardata.bmwgroup.com:9000`, TLS, username=GCID, password=ID token.
> Put the URLs in one `CarDataEndpoints` object so there's a single source of truth.

- [ ] **Step 1: Endpoint constants**

Create `src/main/kotlin/cardata/CarDataEndpoints.kt`:
```kotlin
package io.konektis.cardata

/** Single source of truth for BMW CarData URLs. Fill from BMW's CarData API documentation. */
object CarDataEndpoints {
    const val DEVICE_CODE_URL = "https://customer.bmwgroup.com/gcdm/oauth/device/code"   // confirm
    const val TOKEN_URL = "https://customer.bmwgroup.com/gcdm/oauth/token"               // confirm
    const val SCOPE = "authenticate_user openid cardata:streaming:read"                   // confirm
}
```

- [ ] **Step 2: Implement CarDataAuth IO (device-code bootstrap + refresh)**

Fill the `CarDataAuth` class body (added as a stub in Task 6) using `http` (the injected Ktor client),
`CarDataEndpoints`, the pure parsers from Task 5, and `tokenStore`:
- `suspend fun ensureAuthorized()`: if `tokenStore.get(clientId) != null` return; else POST to
  `DEVICE_CODE_URL` (form: `client_id`, `scope`), parse with `parseDeviceCodeResponse`, **log the
  `verificationUri` + `userCode` at WARN**, then poll `TOKEN_URL` (grant_type
  `urn:ietf:params:oauth:grant-type:device_code`, `device_code`, `client_id`) every `intervalSeconds`
  using `parseTokenResponse` until it returns non-null; on success `tokenStore.save(clientId,
  refreshToken!!, gcid)`.
- `suspend fun currentIdToken(): String`: hold the cached `TokenResponse` + computed `expiresAt`; when
  null or `needsRefresh(expiresAt, Instant.now(), 300)`, POST `TOKEN_URL` (grant_type `refresh_token`,
  `refresh_token` from store, `client_id`), parse, persist a rotated `refresh_token` if present, cache
  the new token. Return the cached `idToken`.
- `fun gcid(): String?` = `tokenStore.get(clientId)?.gcid` (used as the MQTT username).

Use `io.ktor.client.request.post` + `io.ktor.client.request.forms.FormDataContent` +
`io.ktor.client.statement.bodyAsText`.

- [ ] **Step 3: Implement CarDataMqttClient with ktor-mqtt**

Fill the `CarDataMqttClient` class (stub from Task 6). Based on the ktor-mqtt 1.1.0 DSL
(`https://github.com/ukemp/ktor-mqtt` — verify the exact symbols against the README, the one place this
plan can't pin):
- `suspend fun run(onMessage: (String) -> Unit)`: loop forever —
  - build an `MqttClient { }` for `config.brokerHost:config.brokerPort` with **TLS enabled** and
    username = `auth.gcid()`, password = `auth.currentIdToken()`;
  - `connect()`, `subscribe(<VIN topic>)`, and collect the incoming publishes flow, calling
    `onMessage(payload.decodeToString())` for each;
  - wrap in try/catch with bounded backoff; **proactively disconnect & reconnect before the ~1 h token
    expires** (e.g. cancel the collect after ~50 min) so the next connect uses a fresh
    `auth.currentIdToken()`.

- [ ] **Step 4: Wire start()**

Replace the Task-6 stub body of `CarDataService.start()`:
```kotlin
    suspend fun start() {
        if (!config.enabled) return
        auth.ensureAuthorized()
        mqtt.run(::onMessage)
    }
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (reconcile any ktor-mqtt API differences until it compiles).

- [ ] **Step 6: Manual bring-up (one-time, against the real account)**

1. In the CarData portal: generate `client_id`, enable the SoC descriptor, note the exact descriptor name.
2. Set `cardata.enabled: true` + `clientId`/`vin`/`socDescriptor` in the real `config.yaml`; run the server.
3. Approve the device-code URL printed in the logs.
4. Capture one real SoC MQTT message (log the raw payload at DEBUG once). **Confirm it matches the
   assumed `{vin, data:{descriptor:{value}}}` shape**; if not, adjust `parseSoc` + its tests (Task 4)
   and the descriptor config, and re-run `./gradlew test --tests 'io.konektis.cardata.SocParserTest'`.
5. Verify the app shows the percentage.

- [ ] **Step 7: Commit**
```bash
git add src/main/kotlin/cardata/CarDataEndpoints.kt src/main/kotlin/cardata/CarDataAuth.kt src/main/kotlin/cardata/CarDataMqttClient.kt src/main/kotlin/cardata/CarDataService.kt
git commit -m "feat(cardata): live OAuth device-code flow + MQTT SoC subscription"
```

---

## Task 12: Final verification + spec status

- [ ] **Step 1: Full build and test**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Mark the spec implemented**

In `docs/superpowers/specs/2026-06-06-bmw-cardata-soc-display-design.md`, change the `Status:` line to
`Implemented (2026-06-06)`.

- [ ] **Step 3: Commit**
```bash
git add docs/superpowers/specs/2026-06-06-bmw-cardata-soc-display-design.md
git commit -m "docs(cardata): mark SoC-display spec implemented"
```

---

## Companion work (separate Android app repo — NOT in this plan)

In the app's `WsMessage.kt`, add `CarStateUpdate(soc: Int?)` with `@SerialName("CarStateUpdate")`
(must match the server), hold the latest SoC in UI state, and render the car battery % near the charger
card. Tracked separately; ships with a new APK.
