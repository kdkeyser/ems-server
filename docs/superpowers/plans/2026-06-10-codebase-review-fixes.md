# Codebase Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the control-safety, security, and device-driver issues found in the 2026-06-10 codebase review, then remove leftover Ktor-template scaffolding.

**Architecture:** Twelve independent, ordered tasks. Tasks 1–4 are control-safety (stale data, hung polls, crash isolation, lazy Modbus connect). Tasks 5–7 are security (basic auth on web UIs, OCPP registration enforcement, WS auth hardening). Tasks 8–9 are device/OCPP correctness. Tasks 10–12 are validation, cleanup, and docs. Each task is a self-contained green commit; run the full build at the end of every task.

**Tech Stack:** Kotlin/Ktor, kotlinx-coroutines, mockk + kotlin.test (JUnit), Exposed/SQLite, Gradle (`./gradlew`).

**Conventions for the executor:**
- Run all commands from the repo root `/home/koen/Code/ems-server`.
- `./gradlew test --tests "X"` runs one test class; `./gradlew build` runs everything.
- Power sign convention: negative = producing/exporting, positive = consuming/importing (solar is the documented exception, see Task 12).
- Do NOT touch `src/main/kotlin/tools/BatteryWatchdogTest.kt` (manual hardware tool, intentionally kept).
- **Explicitly out of scope:** Webasto keepalive lifecycle changes (the Webasto is not in the active config) and flipping `acceptUnknownIdTags` (would break RFID-card charging starts).

---

### Task 1: Staleness guard in EnergyManager

**Problem:** `DeviceUpdate.collectedAt` is never read anywhere. When a device becomes unreachable, its `update()` throws, the poller catches it, and `getState()` keeps returning the last good reading forever. `EnergyManager.buildEMSState()` therefore never sees `null` after the first successful poll, so the Tier-3 blind release (the battery failsafe) never fires in the most common failure mode.

**Fix:** Treat any reading older than 15 s (3 missed 5 s polls) as missing.

**Files:**
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Test: `src/test/kotlin/ems/EnergyManagerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/ems/EnergyManagerTest.kt`, inside `class EnergyManagerTest`:

```kotlin
@Test fun `stale grid reading is treated as missing and triggers blind release`() = runTest {
    val ts = TestTimeSource()
    val staleMark = ts.markNow()
    ts += 60.seconds // reading is now 60s old — well past STALE_AFTER
    val g = mockk<Grid>().also {
        coEvery { it.update() } just runs
        coEvery { it.getState() } returns DeviceUpdate(staleMark, GridState(Watt(0), Volt(230u)))
    }
    val bat = battery(0)
    val world = World(g, emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
    val m = manager(world)
    repeat(EnergyManager.BLIND_RELEASE_TICKS) { m.tick() }
    coVerify(exactly = 1) { bat.releaseToInverter() }
}

@Test fun `reading just under the staleness threshold is still used`() = runTest {
    val ts = TestTimeSource()
    val freshMark = ts.markNow()
    ts += 10.seconds // under the 15s threshold
    val g = mockk<Grid>().also {
        coEvery { it.update() } just runs
        coEvery { it.getState() } returns DeviceUpdate(freshMark, GridState(Watt(600), Volt(230u)))
    }
    val bat = battery(200)
    val world = World(g, emptyMap(), emptyMap(), emptyMap(), mapOf("b" to bat))
    manager(world).tick()
    // Degraded tier ran on the (fresh) reading: decideDegraded(600, 200) = 200 - 600 = -400
    coVerify { bat.setChargingPower(Watt(-400)) }
}
```

Add these imports to the test file's import block:

```kotlin
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: the `stale grid reading...` test FAILS (release never called — stale data is used today). The `just under threshold` test passes already (that's fine; it pins the boundary).

- [ ] **Step 3: Implement the staleness guard**

In `src/main/kotlin/ems/EnergyManager.kt`:

Add import (the existing `import java.time.Duration` does not clash with this extension import):

```kotlin
import kotlin.time.Duration.Companion.seconds
```

Add this private helper just above `buildEMSState` (it needs `import io.konektis.devices.DeviceUpdate` — add it if not present):

```kotlin
/** The reading, unless it is older than [STALE_AFTER] — then null, same as no reading at all. */
private fun <T> fresh(u: DeviceUpdate<T>?): T? =
    u?.takeIf { it.collectedAt.elapsedNow() <= STALE_AFTER }?.update
```

Replace the body of `buildEMSState()` so every `getState()?.update` access goes through `fresh(...)`:

```kotlin
suspend fun buildEMSState(): EMSState {
    val gridState = fresh(world.grid.getState())
    val solarStates = world.solar.values.mapNotNull { fresh(it.getState())?.power?.value }
    val solarPower = if (solarStates.isEmpty()) null else solarStates.sum()
    val batteryState = fresh(world.batteries.values.firstOrNull()?.getState())
    val chargerState = fresh(world.chargers.values.firstOrNull()?.getState())
    val heatpumpState = fresh(world.smartConsumers.values.firstOrNull()?.getState())

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
}
```

In the `companion object` add the threshold:

```kotlin
companion object {
    const val BLIND_RELEASE_TICKS = 6  // ~30s at 5s cadence
    /** Device readings older than this are treated as missing (a device's getState() retains the
     *  last value forever after its update() starts throwing — without this cap the blind-release
     *  failsafe would never fire when a device drops off the network). 3 missed 5s polls. */
    val STALE_AFTER = 15.seconds
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.konektis.ems.EnergyManagerTest"`
Expected: all PASS (including all pre-existing tests — they create marks with `markNow()` which are fresh).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ems/EnergyManager.kt src/test/kotlin/ems/EnergyManagerTest.kt
git commit -m "fix(ems): treat device readings older than 15s as missing so blind-release fires on device outages"
```

---

### Task 2: Per-poll timeout in DataCollector + HTTP timeout on P1Meter

**Problem:** `DataCollector.refresh()` awaits all device polls with no timeout, and `P1Meter`'s private `HttpClient(CIO)` has no `HttpTimeout` plugin. A TCP black-hole on any device makes `awaitAll()` hang, the refresh loop in `Application.kt` never iterates again, and polling stops entirely (silently).

**Files:**
- Modify: `src/main/kotlin/DataCollector.kt`
- Modify: `src/main/kotlin/devices/grid/P1Meter.kt`
- Test: `src/test/kotlin/DataCollectorHealthTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/DataCollectorHealthTest.kt`, inside `class DataCollectorHealthTest`:

```kotlin
@Test
fun `hung device poll times out and is marked offline`() = runTest {
    val solar = mockk<Solar>()
    coEvery { solar.update() } coAnswers { awaitCancellation() } // never returns
    coEvery { solar.getState() } returns null

    val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)), pollTimeoutMs = 100)
    collector.refresh()

    val health = collector.statusStateFlow.value!!.devices.first { it.name == "Sunny Boy 4" }.health
    assertTrue(health is DeviceHealth.Offline)
}
```

Add import:

```kotlin
import kotlinx.coroutines.awaitCancellation
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.konektis.DataCollectorHealthTest"`
Expected: FAIL to compile (`pollTimeoutMs` parameter does not exist yet).

- [ ] **Step 3: Implement the poll timeout**

In `src/main/kotlin/DataCollector.kt`:

Change the constructor signature:

```kotlin
class DataCollector(
    threads: Int,
    val world: World,
    private val pollTimeoutMs: Long = 10_000,
) : Klogging {
```

Replace the `poll` function:

```kotlin
private suspend fun poll(name: String, category: String, block: suspend () -> DeviceHealth.Online): DeviceStatus {
    return try {
        val health = withTimeout(pollTimeoutMs) { block() }
        healthMap[name] = health
        DeviceStatus(name, health, category)
    } catch (e: TimeoutCancellationException) {
        // A hung device must not stall the whole refresh cycle (awaitAll waits for every poll).
        val health = DeviceHealth.Offline(previousLastSeen(name), "poll timed out after ${pollTimeoutMs}ms")
        healthMap[name] = health
        DeviceStatus(name, health, category)
    } catch (e: Exception) {
        val health = DeviceHealth.Offline(previousLastSeen(name), e.message)
        healthMap[name] = health
        DeviceStatus(name, health, category)
    }
}
```

Add imports:

```kotlin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
```

- [ ] **Step 4: Add HTTP timeouts to the P1 meter client**

In `src/main/kotlin/devices/grid/P1Meter.kt`, inside `P1MeterClient.makeClient()`, add the `HttpTimeout` plugin (mirrors `AppModule.provideHttpClient`):

```kotlin
private fun makeClient(): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
        }
    }
}
```

Add import:

```kotlin
import io.ktor.client.plugins.HttpTimeout
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "io.konektis.DataCollectorHealthTest"`
Expected: all PASS (the new test completes in ~100 ms real time).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/DataCollector.kt src/main/kotlin/devices/grid/P1Meter.kt src/test/kotlin/DataCollectorHealthTest.kt
git commit -m "fix(collector): per-device poll timeout + HTTP timeouts on P1 meter so one hung device cannot stall polling"
```

---

### Task 3: CarDataService failure must not kill the server

**Problem:** `Application.kt` launches `carDataService.start()` inside a plain `coroutineScope`. `CarDataAuth.ensureAuthorized()` throws (`error(...)`) on device-code timeout or token failure; that exception cancels the whole scope — data collector, energy manager, and HTTP server. A display-only feature can take down battery control.

**Fix:** Catch non-cancellation exceptions inside `CarDataService.start()` itself.

**Files:**
- Modify: `src/main/kotlin/cardata/CarDataService.kt`
- Create: `src/test/kotlin/cardata/CarDataServiceStartTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cardata/CarDataServiceStartTest.kt`:

```kotlin
package io.konektis.cardata

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class CarDataServiceStartTest {

    @Test fun `start swallows auth failure instead of crashing the caller`() = runTest {
        val auth = mockk<CarDataAuth>()
        coEvery { auth.ensureAuthorized() } throws IllegalStateException("device authorization timed out")
        val svc = CarDataService(
            CarDataConfig(enabled = true, clientId = "c", vin = "v", socDescriptor = "d"),
            mockk(relaxed = true), auth, mockk(relaxed = true),
        )
        svc.start() // must return normally — a CarData failure may not cancel the EMS control loops
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.konektis.cardata.CarDataServiceStartTest"`
Expected: FAIL with `IllegalStateException: device authorization timed out`.

- [ ] **Step 3: Implement the guard**

In `src/main/kotlin/cardata/CarDataService.kt`, replace `start()`:

```kotlin
/** Bootstrap auth (one-time device approval), then stream SoC with token-aware reconnect. No-op when disabled.
 *  Never throws (except cancellation): this runs as a sibling of the EMS control loops in one
 *  coroutineScope, and an MQTT/auth failure must not cancel battery/charger control. */
suspend fun start() {
    if (!config.enabled) return
    try {
        auth.ensureAuthorized()
        mqtt.run(::onMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e, "BMW CarData stopped: ${e.message} — car SoC will be unavailable")
    }
}
```

Add import:

```kotlin
import kotlin.coroutines.cancellation.CancellationException
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests "io.konektis.cardata.CarDataServiceStartTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cardata/CarDataService.kt src/test/kotlin/cardata/CarDataServiceStartTest.kt
git commit -m "fix(cardata): a CarData auth/MQTT failure no longer cancels the whole server"
```

---

### Task 4: ModbusTCPClient — lazy connect, close broken clients

**Problem:** (a) `makeClient()` calls `connect()` eagerly and runs during `World.fromConfig` at startup, so the server cannot start while any Modbus device is offline. (b) On error, the broken `ModbusTcpClient` is replaced without `disconnect()` — the old Netty transport leaks.

**Files:**
- Modify: `src/main/kotlin/ModbusTCPClient.kt`
- Create: `src/test/kotlin/ModbusTCPClientTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/ModbusTCPClientTest.kt`:

```kotlin
package io.konektis

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ModbusTCPClientTest {

    @Test fun `constructor performs no network IO`() {
        // 203.0.113.1 is TEST-NET-3 (unroutable). With lazy connect the constructor returns
        // immediately; an eager connect would block until the TCP timeout or throw. This is what
        // lets the server start while inverters are offline.
        val elapsed = measureTime { ModbusTCPClient("203.0.113.1") }
        assertTrue(elapsed < 2.seconds, "constructor took $elapsed — is it connecting eagerly?")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.konektis.ModbusTCPClientTest"`
Expected: FAIL — either an exception from `connect()` or elapsed time over 2 s.

- [ ] **Step 3: Implement lazy connect + close-on-replace**

Replace the body of `src/main/kotlin/ModbusTCPClient.kt` with:

```kotlin
package io.konektis

import com.digitalpetri.modbus.client.ModbusTcpClient
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport
import io.klogging.NoCoLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModbusTCPClient(private val host: String) : NoCoLogging {

    private var client = makeClient()
    private val lock = Any()

    private fun makeClient(): ModbusTcpClient {
        // Netty transport — Ktor network sockets don't have a compatible interface with the modbus library
        val transport = NettyTcpClientTransport.create { cfg: NettyClientTransportConfig.Builder ->
            cfg.hostname = host
            cfg.port = 502
        }
        // No connect() here: withClient connects lazily, so constructing device drivers at startup
        // succeeds even while the device is offline.
        return ModbusTcpClient.create(transport)
    }

    suspend fun <T> withClient(f: (client: ModbusTcpClient) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!client.isConnected) client.connect()
            try {
                f(client)
            } catch (e: Exception) {
                logger.warn("Modbus connection error for $host, reconnecting: ${e.message}")
                runCatching { client.disconnect() } // release the broken transport before replacing it
                try { client = makeClient() } catch (_: Exception) { }
                throw e
            }
        }
    }
}
```

- [ ] **Step 4: Run the test, then the full build**

Run: `./gradlew test --tests "io.konektis.ModbusTCPClientTest"` — expected: PASS.
Run: `./gradlew build` — expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ModbusTCPClient.kt src/test/kotlin/ModbusTCPClientTest.kt
git commit -m "fix(modbus): connect lazily (server starts with devices offline) and close broken clients on reconnect"
```

---

### Task 5: Basic auth on /ocpp-ui and /status

**Problem:** `/ocpp-ui`, all of `/ocpp-ui/api/*` (remote start/stop, charger reset, manual current override, idTag allow-list management) and `/status` are completely unauthenticated. The server is published to the internet through a Cloudflare tunnel; the only gate is an edge WAF header rule. The `auth-basic` provider already exists (used by `/users` and `/history`).

**Design decision:** the two read-only WebSocket pushes (`/ocpp-ui/ws`, `/status-ws`) stay unauthenticated — browser JS cannot attach Basic credentials to a WS upgrade, and they expose telemetry only. Everything else goes behind `authenticate("auth-basic")`. (Browsers re-send cached Basic credentials on `fetch()` to the same origin, so the pages keep working unchanged.)

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppWebUi.kt`
- Modify: `src/main/kotlin/StatusPage.kt`
- Test: `src/test/kotlin/ocpp/OcppWebUiTest.kt`
- Test: `src/test/kotlin/StatusPageTest.kt`

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/ocpp/OcppWebUiTest.kt`:

1. In `testModule()`, install the auth provider — add this line right after `install(ContentNegotiation) { json() }`:

```kotlin
io.konektis.configureSecurity(io.konektis.config.WebSocketConfig("admin", "secret"))
```

2. Add this test:

```kotlin
@Test
fun rejectsUnauthenticatedRequests() = testApplication {
    application { testModule() }
    assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui/api/state").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/ocpp-ui/api/idtags").status)
}
```

3. Add `basicAuth("admin", "secret")` to **every existing** `client.get`/`client.post`/`client.delete` request builder in this file (the request-builder lambda; create one where a call has none), e.g.:

```kotlin
val resp = client.get("/ocpp-ui") { basicAuth("admin", "secret") }
```

```kotlin
val post = client.post("/ocpp-ui/api/idtags") {
    basicAuth("admin", "secret")
    contentType(ContentType.Application.Json)
    setBody("""{"idTag":"TAG1","status":"Accepted"}""")
}
```

Add import: `import io.ktor.client.request.basicAuth` (covered by the existing `io.ktor.client.request.*` import if present).

In `src/test/kotlin/StatusPageTest.kt`:

1. In `installStatusPage(...)`, add inside the `application { ... }` block, before `configureStatusPage(flow)`:

```kotlin
configureSecurity(io.konektis.config.WebSocketConfig("admin", "secret"))
```

2. Add `{ basicAuth("admin", "secret") }` to the two `client.get("/status")` calls (`import io.ktor.client.request.basicAuth` is covered by the existing `io.ktor.client.request.*` import).

3. Add this test:

```kotlin
@Test
fun `GET slash status requires auth`() = testApplication {
    installStatusPage(emptyFlow())
    assertEquals(HttpStatusCode.Unauthorized, client.get("/status").status)
}
```

Leave the `/status-ws` tests untouched — that endpoint stays open.

- [ ] **Step 2: Run the tests to verify the new ones fail**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest" --tests "io.konektis.StatusPageTest"`
Expected: the new auth tests FAIL (200 instead of 401); existing tests still pass (basic auth is ignored when no provider guards the route... if any existing test fails on the doubled `configureSecurity`, fix per the error).

- [ ] **Step 3: Implement the auth wrapping**

In `src/main/kotlin/ocpp/OcppWebUi.kt`, restructure `configureOcppWebUi` so the WS stays open and everything else is wrapped (route contents stay byte-for-byte identical, only the nesting changes):

```kotlin
fun Application.configureOcppWebUi(service: OcppService, energyManager: EnergyManager) {
    val json = Json { encodeDefaults = true }
    routing {
        // Live status push stays unauthenticated: browser JS cannot attach Basic credentials to a
        // WebSocket upgrade, and this is read-only telemetry. Control stays behind auth below.
        webSocket("/ocpp-ui/ws") {
            service.stateFlow.collect { send(Json.encodeToString(it)) }
        }

        authenticate("auth-basic") {
            get("/ocpp-ui") {
                val bytes = object {}::class.java.getResourceAsStream("/ocpp.html")!!.readBytes()
                call.respondBytes(bytes, ContentType.Text.Html)
            }

            route("/ocpp-ui/api") {
                // ... all existing /ocpp-ui/api routes, unchanged ...
            }
        }
    }
}
```

Add import: `import io.ktor.server.auth.authenticate`.

In `src/main/kotlin/StatusPage.kt`:

```kotlin
fun Application.configureStatusPage(statusFlow: Flow<StatusState?>) {
    routing {
        authenticate("auth-basic") {
            get("/status") {
                val bytes = object {}::class.java.getResourceAsStream("/status.html")!!.readBytes()
                call.respondBytes(bytes, ContentType.Text.Html)
            }
        }
        // Read-only telemetry; browser JS cannot send Basic credentials on a WS upgrade.
        webSocket("/status-ws") {
            statusFlow.filterNotNull().collect { state ->
                send(Json.encodeToString(state))
            }
        }
    }
}
```

Add import: `import io.ktor.server.auth.authenticate`.

Note: `Application.module` in `Application.kt` calls `configureSecurity` before `configureStatusPage`/`configureOcppWebUi`, so the provider is installed before `authenticate` references it. No change needed there.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppWebUiTest" --tests "io.konektis.StatusPageTest"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ocpp/OcppWebUi.kt src/main/kotlin/StatusPage.kt src/test/kotlin/ocpp/OcppWebUiTest.kt src/test/kotlin/StatusPageTest.kt
git commit -m "fix(security): require basic auth on /ocpp-ui pages+API and /status"
```

---

### Task 6: OCPP — enforce registration, seed the EMS idTag, stop auto-accepting unknown charge points

**Problem:** Nothing checks `registrationStatus` after BootNotification — a Pending (never-accepted) charge point can open transactions and push MeterValues, i.e. anything that can reach `/ocpp/{id}` can impersonate the charger and feed fabricated power data into the control loop. Also `acceptUnknownChargePoints` defaults to `true`.

**Fix (three parts):**
1. Gate every OCPP action except `BootNotification`/`Heartbeat` on the charge point being accepted (live session Accepted, or persisted accepted — covers reconnects without a fresh boot).
2. Seed an `"EMS"` idTag as Accepted at startup (the EMS authorises its own RemoteStartTransactions with it; today it only works because `acceptUnknownIdTags` is on).
3. Flip the `acceptUnknownChargePoints` **default** to `false`. (Deployment-safe: the live CP01 is already `accepted=true` in the NAS SQLite. `acceptUnknownIdTags` stays `true` — flipping it would break RFID-card starts.)

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Modify: `src/main/kotlin/ocpp/OcppServer.kt`
- Modify: `src/main/kotlin/config/Config.kt`
- Modify: `src/main/kotlin/devices/charger/OcppCharger.kt`
- Test: `src/test/kotlin/ocpp/OcppServerTest.kt`, `src/test/kotlin/ocpp/OcppServiceTest.kt`, plus mechanical `OcppConfig(...)` updates in other test files

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/ocpp/OcppServerTest.kt` inside the class:

```kotlin
@Test
fun pendingChargePointGetsSecurityErrorForGatedActions() = testApplication {
    application {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 60.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        val db = io.konektis.ocpp.freshTestDb()
        val cfg = OcppConfig(enabled = true, heartbeatInterval = 300, connectionTimeout = 60,
            callTimeoutSeconds = 1, acceptUnknownChargePoints = false, autoProbeOnBoot = false)
        val service = OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db), cfg)
            .also { it.initStores() }
        configureOcppServer(service)
    }
    val client = createClient { install(ClientWebSockets) }
    client.webSocket("/ocpp/ROGUE", request = { header(HttpHeaders.SecWebSocketProtocol, "ocpp1.6") }) {
        // BootNotification is allowed but answered Pending (charge point unknown, auto-accept off).
        send(Frame.Text("""[2,"1","BootNotification",{"chargePointVendor":"X","chargePointModel":"Y"}]"""))
        val boot = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray
        assertEquals("Pending", boot[2].jsonObject["status"]?.jsonPrimitive?.content)
        // StatusNotification is gated: a non-accepted charge point gets CALL_ERROR SecurityError.
        send(Frame.Text("""[2,"2","StatusNotification",{"connectorId":1,"errorCode":"NoError","status":"Available"}]"""))
        val err = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonArray
        assertEquals(4, err[0].jsonPrimitive.int) // CALL_ERROR
        assertEquals("SecurityError", err[2].jsonPrimitive.content)
    }
}
```

Add to `src/test/kotlin/ocpp/OcppServiceTest.kt` (match its existing construction style; if it has a helper for building the service, use that):

```kotlin
@Test
fun `initStores seeds the EMS idTag as Accepted`() = runTest {
    val db = freshTestDb()
    val svc = OcppService(
        ChargePointStore(db), IdTagStore(db), TransactionStore(db),
        OcppConfig(true, 300, 60, acceptUnknownChargePoints = true, autoProbeOnBoot = false),
    ).also { it.initStores() }
    val tag = svc.listIdTags().single { it.idTag == "EMS" }
    assertEquals("Accepted", tag.status)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServerTest" --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: both new tests FAIL (gated action currently succeeds with CALL_RESULT; no EMS idTag exists).

- [ ] **Step 3: Implement**

(a) In `src/main/kotlin/config/Config.kt`, change the default:

```kotlin
val acceptUnknownChargePoints: Boolean = false,
```

(b) In `src/main/kotlin/ocpp/OcppService.kt`:

Add to `initStores()` (needs `import kotlinx.coroutines.runBlocking`):

```kotlin
fun initStores() {
    chargePoints.init(); idTags.init(); transactions.init()
    transactionIdCounter.set(transactions.maxTransactionId() + 1)
    // The EMS authorises its own RemoteStartTransactions with this tag; seed it so charging
    // keeps working even when acceptUnknownIdTags is turned off.
    runBlocking { if (idTags.get(EMS_ID_TAG) == null) idTags.put(EMS_ID_TAG, "Accepted") }
}
```

Add a companion object at the bottom of the class:

```kotlin
companion object {
    /** idTag the EMS uses for transactions it starts itself. Seeded as Accepted at startup. */
    const val EMS_ID_TAG = "EMS"
}
```

Add this method near `isPowerControlCapable`:

```kotlin
/** True when actions beyond BootNotification/Heartbeat may be processed: the charge point was
 *  accepted in this session, or is persisted as accepted (covers reconnects without a fresh boot). */
suspend fun isCallAllowed(chargePointId: String): Boolean {
    if (sessions[chargePointId]?.registrationStatus == RegistrationStatus.Accepted) return true
    return chargePoints.get(chargePointId)?.accepted == true
}
```

(c) In `src/main/kotlin/ocpp/OcppServer.kt`, at the very top of `handleCall`, before the `when`:

```kotlin
private suspend fun handleCall(chargePointId: String, uniqueId: String, action: String, payload: JsonObject): String {
    val gated = action != Action.BootNotification.name && action != Action.Heartbeat.name
    if (gated && !service.isCallAllowed(chargePointId)) {
        return errorResponse(uniqueId, ErrorCode.SecurityError, "Charge point not accepted")
    }
    // ... existing when block unchanged ...
```

(d) In `src/main/kotlin/devices/charger/OcppCharger.kt`, reference the constant instead of a magic string:

```kotlin
private val idTag: String = OcppService.EMS_ID_TAG,
```

- [ ] **Step 4: Fix the test configs that relied on auto-accept**

The default flip breaks tests that construct `OcppConfig` and expect boot auto-acceptance. Find them:

Run: `grep -rn "OcppConfig(" src/test --include="*.kt"`

For **every** construction found, add `acceptUnknownChargePoints = true` unless the test already passes it explicitly (the new `pendingChargePoint...` test passes `false` — leave that one). Expected files: `OcppServerTest.kt`, `OcppWebUiTest.kt`, `OcppServiceTest.kt`, `OcppCommandsTest.kt`, `OcppChargerTest.kt`, `OcppEmsIntegrationTest.kt` (adjust to what grep actually finds).

- [ ] **Step 5: Run the whole OCPP test suite**

Run: `./gradlew test --tests "io.konektis.ocpp.*" --tests "io.konektis.devices.charger.*"`
Expected: all PASS.

- [ ] **Step 6: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/kotlin/ocpp src/main/kotlin/config/Config.kt src/main/kotlin/devices/charger/OcppCharger.kt src/test/kotlin
git commit -m "fix(ocpp): gate actions on accepted registration, seed EMS idTag, default acceptUnknownChargePoints=false"
```

---

### Task 7: WebSocket auth hardening in Sockets.kt

**Problem:** `authenticated` is a plain `var` captured by five coroutines that may run on different threads (no visibility guarantee), and a failed password gets an instant answer (free brute-force oracle).

**Files:**
- Modify: `src/main/kotlin/Sockets.kt`
- Create: `src/test/kotlin/SocketsAuthTest.kt`

- [ ] **Step 1: Write the test (pins current correct behavior, guards the refactor)**

Create `src/test/kotlin/SocketsAuthTest.kt`:

```kotlin
package io.konektis

import io.konektis.config.WebSocketConfig
import io.konektis.devices.World
import io.konektis.ems.EnergyManager
import io.konektis.ems.FakeChargerControlStore
import io.konektis.ems.SurplusPriorityStrategy
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.*
import io.ktor.websocket.*
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue

class SocketsAuthTest {

    private fun ApplicationTestBuilder.installSockets() {
        application {
            val em = EnergyManager(
                World(mockk(relaxed = true), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
                mockk(relaxed = true), SurplusPriorityStrategy(), FakeChargerControlStore(),
            )
            configureSockets(em, WebSocketConfig("user", "pw"))
        }
    }

    @Test fun `wrong password gets Unauthorized`() = testApplication {
        installSockets()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/ws") {
            send(Frame.Text("""{"type":"Authenticate","username":"user","password":"wrong"}"""))
            val reply = (incoming.receive() as Frame.Text).readText()
            assertTrue(reply.contains("Unauthorized"), "expected Unauthorized, got: $reply")
        }
    }

    @Test fun `correct password gets Authenticated`() = testApplication {
        installSockets()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/ws") {
            send(Frame.Text("""{"type":"Authenticate","username":"user","password":"pw"}"""))
            val reply = (incoming.receive() as Frame.Text).readText()
            assertTrue(reply.contains("Authenticated"), "expected Authenticated, got: $reply")
        }
    }
}
```

(If `FakeChargerControlStore` is not visible from package `io.konektis`, check `src/test/kotlin/ems/FakeChargerControlStore.kt` and adjust the import/visibility accordingly.)

- [ ] **Step 2: Run it — should pass already (it pins behavior)**

Run: `./gradlew test --tests "io.konektis.SocketsAuthTest"`
Expected: PASS. (If the wrong-password test races the close, receive the first Text frame as shown — the server sends Unauthorized before closing.)

- [ ] **Step 3: Refactor Sockets.kt**

In `src/main/kotlin/Sockets.kt`:

1. Replace `var authenticated = false` with:

```kotlin
// Shared across the collector coroutines below, which may run on different threads.
val authenticated = java.util.concurrent.atomic.AtomicBoolean(false)
```

2. Replace every read `if (authenticated)` with `if (authenticated.get())` (5 occurrences: the four collector jobs and any checks in the incoming handler), every `if (!authenticated)` with `if (!authenticated.get())` (3 occurrences), and the assignment `authenticated = true` with `authenticated.set(true)`.

3. In the failed-authentication branch (the `else` that sends `Message.Unauthorized`), add a delay as the first statement:

```kotlin
} else {
    delay(1000) // slow down brute-force attempts
    send(Json.encodeToString(Message.Unauthorized(message.username) as Message))
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
}
```

Add import: `import kotlinx.coroutines.delay`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.konektis.SocketsAuthTest"`
Expected: PASS (wrong-password test now takes ~1 s).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/Sockets.kt src/test/kotlin/SocketsAuthTest.kt
git commit -m "fix(ws): atomic authenticated flag + 1s delay on failed auth"
```

---

### Task 8: Daikin heat pump — write the 0 W cap, skip duplicate writes

**Problem:** `DaikinHomeHub.setConsumeMode` only writes the max-power register (56) when `maxPower > 0`. `SuggestConsumeUpTo(Watt(0))` — exactly the deepest-deficit case — writes SG mode 1 but leaves the *previous, higher* power suggestion active. Also both registers are rewritten every 5 s tick with no change detection.

**Fix:** Restructure with a testable Modbus seam (mirror the `BatteryModbus` pattern in `SMABattery.kt`), always write register 56 in SG mode, and dedupe unchanged writes.

**Files:**
- Modify: `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt` (full rewrite below)
- Create: `src/test/kotlin/devices/smartConsumer/DaikinHeatpumpTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/devices/smartConsumer/DaikinHeatpumpTest.kt`:

```kotlin
package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// If this constructor does not compile, mirror how SMABatteryGuardTest fakes ReadInputRegistersResponse.
private class FakeHeatpumpModbus : HeatpumpModbus {
    val writes = mutableListOf<Pair<Int, Int>>()
    override suspend fun readInput(register: Int, count: Int) =
        ReadInputRegistersResponse(ByteArray(count * 2))
    override suspend fun writeHolding(register: Int, value: Int) { writes.add(register to value) }
}

class DaikinHeatpumpTest {

    @Test fun `zero-watt suggestion also writes the max-power register`() = runTest {
        val fake = FakeHeatpumpModbus()
        DaikinHeatpump(fake).setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(0)))
        assertEquals(listOf(55 to 1, 56 to 0), fake.writes)
    }

    @Test fun `unchanged command is not rewritten every tick`() = runTest {
        val fake = FakeHeatpumpModbus()
        val hp = DaikinHeatpump(fake)
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1500)))
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1500)))
        assertEquals(listOf(55 to 1, 56 to 1500), fake.writes)
    }

    @Test fun `unrestricted writes only the mode register`() = runTest {
        val fake = FakeHeatpumpModbus()
        val hp = DaikinHeatpump(fake)
        hp.setConsumeMode(ConsumeMode.SuggestConsumeUpTo(Watt(1000)))
        hp.setConsumeMode(ConsumeMode.Unrestricted)
        assertEquals(listOf(55 to 1, 56 to 1000, 55 to 0), fake.writes)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `./gradlew test --tests "io.konektis.devices.Heatpump.DaikinHeatpumpTest"`
Expected: compile FAILURE (`HeatpumpModbus` does not exist).

- [ ] **Step 3: Rewrite the driver with the seam**

Replace the full contents of `src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt` with:

```kotlin
package io.konektis.devices.Heatpump

import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest
import io.klogging.Klogging
import io.konektis.GlobalTimeSource
import io.konektis.ModbusTCPClient
import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Watt
import io.konektis.devices.smartConsumers.ConsumeMode
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.smartConsumers.SmartConsumerState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlincrypto.bitops.endian.Endian

// Current total heat pump power consumption, unit 10W (multiply raw value by 10 for W)
private const val MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER: Int = 50
// SG-Ready mode: 0=normal, 1=lock (min operation), 2=recommended, 3=max operation
private const val MODBUS_HOLDING_REGISTER_SMART_GRID: Int = 55
// Maximum power suggestion to heat pump when in SG-Ready mode, W
private const val MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER: Int = 56

/** Minimal seam over the raw Modbus ops, so the SG-Ready write logic is testable. */
interface HeatpumpModbus {
    suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse
    suspend fun writeHolding(register: Int, value: Int)
}

private class RealHeatpumpModbus(host: String) : HeatpumpModbus {
    private val client = ModbusTCPClient(host)

    override suspend fun readInput(register: Int, count: Int): ReadInputRegistersResponse =
        client.withClient { it.readInputRegisters(1, ReadInputRegistersRequest(register, count)) }

    override suspend fun writeHolding(register: Int, value: Int) {
        client.withClient { it.writeSingleRegister(1, WriteSingleRegisterRequest(register, value)) }
    }
}

class DaikinHeatpump(private val modbus: HeatpumpModbus) : Klogging, SmartConsumer {
    constructor(host: String) : this(RealHeatpumpModbus(host))

    private var internalState: DeviceUpdate<SmartConsumerState>? = null
    private val mutex = Mutex()
    /** Last (mode, maxPower) written, to skip rewriting the same command every 5s tick. */
    private var lastWritten: Pair<Int, Int>? = null

    override suspend fun update() {
        mutex.withLock {
            internalState = DeviceUpdate(GlobalTimeSource.source.markNow(), readState())
            logger.trace { "DaikinHeatpump: $internalState" }
        }
    }

    override suspend fun getState(): DeviceUpdate<SmartConsumerState>? {
        mutex.withLock {
            return internalState
        }
    }

    override suspend fun setConsumeMode(consumeMode: ConsumeMode) {
        mutex.withLock {
            val (mode, maxPower) = when (consumeMode) {
                is ConsumeMode.Unrestricted -> 0 to 0
                is ConsumeMode.SuggestConsumeUpTo -> 1 to consumeMode.power.value
            }
            if (lastWritten == mode to maxPower) return@withLock
            modbus.writeHolding(MODBUS_HOLDING_REGISTER_SMART_GRID, mode)
            // Always write the cap in SG mode — including 0 W. Skipping 0 left the previous,
            // higher suggestion active exactly when the EMS wanted full throttling.
            if (mode == 1) modbus.writeHolding(MODBUS_HOLDING_REGISTER_SMART_GRID_MAX_POWER, maxPower)
            lastWritten = mode to maxPower
        }
    }

    private suspend fun readState(): SmartConsumerState {
        val result = modbus.readInput(MODBUS_INPUT_REGISTER_CURRENT_TOTAL_POWER, 1)
        val usage = Endian.Big.shortFrom(result.registers, 0) * 10
        return SmartConsumerState(Watt(usage), ConsumeMode.Unrestricted)
    }
}
```

(This removes the private `DaikinHomeHub` class; `World.fromConfig` still calls `DaikinHeatpump(it.host)` via the secondary constructor, unchanged.)

- [ ] **Step 4: Run the tests and full build**

Run: `./gradlew test --tests "io.konektis.devices.Heatpump.DaikinHeatpumpTest"` — expected: PASS.
Run: `./gradlew build` — expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/devices/smartConsumer/DaikinHomeHub.kt src/test/kotlin/devices/smartConsumer/DaikinHeatpumpTest.kt
git commit -m "fix(daikin): write the 0W cap (was silently skipped) and dedupe unchanged SG-Ready writes"
```

---

### Task 9: OCPP — recovered transaction ids must not collide with the counter

**Problem:** `handleMeterValues` recovers a charger-side transaction id after a server restart, but `transactionIdCounter` (seeded from the DB max) may be at or below that id — the next `handleStartTransaction` could hand out a duplicate.

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Test: `src/test/kotlin/ocpp/OcppServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/ocpp/OcppServiceTest.kt` (match the file's existing service-construction style):

```kotlin
@Test
fun `recovered transaction id bumps the counter past it`() = runTest {
    val db = freshTestDb()
    val svc = OcppService(
        ChargePointStore(db), IdTagStore(db), TransactionStore(db),
        OcppConfig(true, 300, 60, acceptUnknownChargePoints = true, autoProbeOnBoot = false),
    ).also { it.initStores() }
    svc.registerSession("CP1", mockk(relaxed = true))
    // Charger reports an in-flight transaction the server never issued (recovered after restart).
    svc.handleMeterValues("CP1", MeterValuesRequest(connectorId = 1, transactionId = 500, meterValue = emptyList()))
    val resp = svc.handleStartTransaction(
        "CP1", StartTransactionRequest(connectorId = 1, idTag = "EMS", meterStart = 0, timestamp = "2026-06-10T00:00:00Z"),
    )
    assertTrue(resp.transactionId > 500, "expected id > 500, got ${resp.transactionId}")
}
```

Needed imports (if missing): `io.mockk.mockk`, `kotlin.test.assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: the new test FAILS (`resp.transactionId` is 1).

- [ ] **Step 3: Implement**

In `src/main/kotlin/ocpp/OcppService.kt`, in `handleMeterValues`, extend the existing recovery block:

```kotlin
request.transactionId?.let { txId ->
    if (connector != null && connector.currentTransactionId != txId) connector.currentTransactionId = txId
    // A recovered (charger-issued) id may be at/above our counter; bump it so the next
    // StartTransaction cannot hand out a duplicate id.
    transactionIdCounter.updateAndGet { maxOf(it, txId + 1) }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests "io.konektis.ocpp.OcppServiceTest"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/ocpp/OcppService.kt src/test/kotlin/ocpp/OcppServiceTest.kt
git commit -m "fix(ocpp): bump transaction counter past recovered charger-side ids to avoid duplicates"
```

---

### Task 10: Startup config warnings (default password, multi-device)

**Problem:** The default `user`/`password` WebSocket credentials ship in the jar with no warning, and configuring multiple chargers/batteries/heat pumps silently half-works (only the first is used for state/keys, but commands go to all).

**Files:**
- Modify: `src/main/kotlin/config/Config.kt`
- Modify: `src/main/kotlin/Application.kt`
- Test: `src/test/kotlin/config/ConfigTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/config/ConfigTest.kt` (top-level helpers can go above the class; mind the `Devices` name — use the config one):

```kotlin
private fun warnCfg(
    websocket: WebSocketConfig = WebSocketConfig("user", "s3cret"),
    devices: Devices = Devices(),
) = Config(
    grid = Grid(GridMeterType.P1HomeWizard, GridType.Phase1, "host"),
    devices = devices,
    ocpp = OcppConfig(true, 300, 60),
    websocket = websocket,
)
```

And inside the test class:

```kotlin
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
```

Ensure the imports cover `io.konektis.config.*` and `kotlin.test.assertTrue`.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: compile FAILURE (`startupWarnings` does not exist).

- [ ] **Step 3: Implement**

In `src/main/kotlin/config/Config.kt`, add at the bottom of the file:

```kotlin
/**
 * Human-readable misconfiguration warnings, logged once at startup. Multi-device lists are
 * accepted by the schema but the EnergyManager only reads the FIRST charger/battery/heat pump
 * for state and control keys (commands fan out to all) — warn instead of silently half-working.
 */
fun Config.startupWarnings(): List<String> = buildList {
    if (websocket.password == "password")
        add("Default WebSocket password in use — set websocket.password in config.yaml")
    if (devices.charger.size > 1)
        add("Multiple chargers configured; only '${devices.charger.first().name}' drives EMS control")
    if (devices.battery.size > 1)
        add("Multiple batteries configured; only '${devices.battery.first().name}' is read into EMSState")
    if (devices.heatPump.size > 1)
        add("Multiple heat pumps configured; only '${devices.heatPump.first().name}' is read into EMSState")
}
```

In `src/main/kotlin/Application.kt`, after the existing block of `logger.info(...)` config lines (right after the `Refresh interval` line), add:

```kotlin
config.startupWarnings().forEach { logger.warn(it) }
```

Add import: `import io.konektis.config.startupWarnings`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "io.konektis.config.ConfigTest"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/config/Config.kt src/main/kotlin/Application.kt src/test/kotlin/config/ConfigTest.kt
git commit -m "feat(config): warn at startup on default password and silently-ignored extra devices"
```

---

### Task 11: Delete Ktor template leftovers, fix config.yaml duplicate key, dedupe OCPP routes

**Problem:** Leftover generator scaffolding: an unused `/users` CRUD that stores plaintext passwords in SQLite, a no-op rate limiter, unused Sessions, a dead demo function. Separately, `src/main/resources/config.yaml` has **two** `charger:` keys under `devices:` — YAML last-wins, so the Webasto entry is silently dead. And `OcppServer.kt` has two copy-pasted webSocket blocks.

**Files:**
- Delete: `src/main/kotlin/Databases.kt`, `src/main/kotlin/UsersSchema.kt`, `src/main/kotlin/Administration.kt`
- Modify: `src/main/kotlin/Application.kt`, `src/main/kotlin/Security.kt`, `src/main/kotlin/Messages.kt`, `src/main/kotlin/ocpp/OcppServer.kt`, `src/main/resources/config.yaml`

- [ ] **Step 1: Verify nothing else references the deleted code**

Run: `grep -rn "configureDatabases\|configureAdministration\|UserService\|ExposedUser\|MySession\|randomPowerUsageUpdate" src --include="*.kt" | grep -v "src/main/kotlin/Databases.kt\|src/main/kotlin/UsersSchema.kt\|src/main/kotlin/Administration.kt\|src/main/kotlin/Security.kt\|src/main/kotlin/Messages.kt"`
Expected: only the two call sites in `Application.kt`. If anything else shows up, update those call sites too.

- [ ] **Step 2: Delete and detach**

```bash
git rm src/main/kotlin/Databases.kt src/main/kotlin/UsersSchema.kt src/main/kotlin/Administration.kt
```

In `src/main/kotlin/Application.kt`:
- Remove the `configureAdministration()` and `configureDatabases(database)` lines from `Application.module`.
- Remove the now-unused `database: Database` parameter from `module(...)`, the `component.database` argument at the call site in `Main.main`, and the `import org.jetbrains.exposed.sql.Database` import. (The Database itself is still provided by DI to the OCPP/cardata stores — only the route module stops needing it.)

In `src/main/kotlin/Security.kt`:
- Remove the `install(Sessions) { ... }` block and the `MySession` data class; keep the `authentication { basic(...) }` block exactly as is.
- Trim the imports the file no longer needs (the template pasted ~20; keep only what compiles: `io.ktor.server.application.*`, `io.ktor.server.auth.*`, `io.konektis.config.WebSocketConfig`).

In `src/main/kotlin/Messages.kt`:
- Delete the `randomPowerUsageUpdate()` function and the now-unused `import kotlin.random.Random`.

- [ ] **Step 3: Dedupe the OCPP routes**

In `src/main/kotlin/ocpp/OcppServer.kt`, replace the two identical `webSocket(...)` blocks inside `routing { ... }` with:

```kotlin
routing {
    // Same handler on both paths: some chargers append the OCPP version to the URL.
    listOf("/ocpp/{chargePointId}", "/ocpp/1.6/{chargePointId}").forEach { path ->
        webSocket(path, protocol = OCPP_SUBPROTOCOL) {
            val chargePointId = call.parameters["chargePointId"] ?: run {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing charge point ID")); return@webSocket
            }
            handler.handleConnection(chargePointId, this)
        }
    }
}
```

- [ ] **Step 4: Fix the duplicate YAML key**

In `src/main/resources/config.yaml`, the `devices:` section currently contains two `charger:` keys (one Webasto, one OCPP). Keep a **single** `charger:` key with only the OCPP entry (that is what YAML resolves to today, so behavior is unchanged):

```yaml
  charger:
    - type: OCPP
      name: Garage
      chargePointId: CP01
      chargingCurrent:
        min: 6.0
        max: 32.0
```

Delete the Webasto `charger:` block entirely (it was silently dead; the device entry remains in git history if ever needed again).

- [ ] **Step 5: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: remove Ktor template leftovers (users CRUD, sessions, dead rate limiter), fix duplicate charger key in config.yaml, dedupe OCPP routes"
```

---

### Task 12: Document the solar sign-convention exception

**Problem:** CLAUDE.md and `Messages.kt` state *negative = producing* for **all** power fields, but `SMASolar` reports production as **positive** watts, and that flows unmodified into `EMSState.solarPower` and the WS protocol — which the Android app already renders correctly. The docs are wrong, not the code; flipping the sign now would break the app. The next strategy to read `WorldSnapshot.solarPower` (currently unused) must not be misled.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `src/main/kotlin/Messages.kt`
- Modify: `src/main/kotlin/devices/solar/SMASolar.kt`

- [ ] **Step 1: Update CLAUDE.md**

In the **Power Sign Convention** section, replace the existing paragraph with:

```markdown
All `power: Int` fields in `EMSState` and `Update` use: **negative = producing/exporting, positive = consuming/importing**. Grid power from the P1 meter follows this directly.

**Exception — solar:** `solarPower` is reported as **positive watts while producing** (SMA register 30775 is an unsigned production value, and the Android app renders it as-is). Do not feed `solarPower` into control math expecting the negative-producing convention.
```

- [ ] **Step 2: Update the code comments**

In `src/main/kotlin/Messages.kt`, change the comment above `data class Update` to:

```kotlin
// power: negative = producing/exporting, positive = consuming/importing.
// Exception: SOLAR is positive while producing (see CLAUDE.md "Power Sign Convention").
```

In `src/main/kotlin/devices/solar/SMASolar.kt`, extend the comment above the return in `getSolarState()`:

```kotlin
// SMA returns Int.MIN_VALUE when the inverter is off (no sunlight); treat as 0W.
// NOTE: production is reported POSITIVE here — the documented exception to the
// negative-=-producing convention (the app and EMSState consumers expect it this way).
```

- [ ] **Step 3: Build (docs/comments only, but keep the invariant: every task ends green)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md src/main/kotlin/Messages.kt src/main/kotlin/devices/solar/SMASolar.kt
git commit -m "docs: document the solar positive-while-producing sign exception"
```

---

## Final verification

- [ ] Run the complete build one last time: `./gradlew build` — expected: BUILD SUCCESSFUL.
- [ ] Review the commit list: `git log --oneline main..HEAD` (or `git log --oneline -12` if working on main) — expected: ~12 commits matching the tasks above.

## Deployment notes (for the human, not the executor)

- Task 5 changes live behavior: `/ocpp-ui` and `/status` will prompt for the basic-auth credentials (the `websocket.username`/`password` from the mounted config). The Android app is unaffected (it authenticates over `/ws` as before).
- Task 6 is deployment-safe for the existing charger: CP01 is already `accepted=true` in the NAS SQLite, so `acceptUnknownChargePoints=false` does not lock it out. A *new* charge point will sit Pending until accepted in `/ocpp-ui`.
- Task 1/2 change failure behavior on the live system: after ~15 s of a dead P1 meter or battery the EMS now goes blind and (after ~30 s more) releases the battery to the inverter — this is the intended failsafe finally working.
