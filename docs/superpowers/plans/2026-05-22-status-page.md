# Status Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-time HTML status page at `/status` that shows per-device connectivity health and current energy distribution, updated live via a `/status-ws` WebSocket.

**Architecture:** `DataCollector.refresh()` wraps each device poll in a per-device try/catch, recording `DeviceHealth.Online` or `DeviceHealth.Offline` in a map. After each refresh it emits a `StatusState` on a `MutableStateFlow`. A new Ktor plugin (`StatusPage.kt`) registers `GET /status` (serves a self-contained HTML file) and `WebSocket /status-ws` (streams `StatusState` JSON to browsers). The existing `/ws` endpoint and mobile protocol are untouched.

**Tech Stack:** Kotlin, Ktor 3.x (WebSockets, routing), kotlinx.serialization, kotlinx.coroutines, JUnit4 + mockk + ktor-server-test-host

---

## File Map

### New files
- `src/main/kotlin/StatusState.kt` — `DeviceHealth` sealed class, `DeviceStatus`, `StatusState` data classes with `@Serializable`
- `src/main/kotlin/StatusPage.kt` — `fun Application.configureStatusPage(statusFlow: Flow<StatusState?>)` with `GET /status` and `WebSocket /status-ws`
- `src/main/resources/status.html` — self-contained HTML/CSS/JS status page
- `src/test/kotlin/StatusStateTest.kt` — serialization round-trip tests
- `src/test/kotlin/DataCollectorHealthTest.kt` — unit tests for per-device health tracking
- `src/test/kotlin/StatusPageTest.kt` — Ktor integration tests for both routes

### Modified files
- `src/main/kotlin/DataCollector.kt` — add `healthMap`, `statusStateFlow`, replace flat `awaitAll` with per-device `poll()` helper
- `src/main/kotlin/Application.kt` — add `statusFlow: Flow<StatusState?>` param to `module()`, call `configureStatusPage(statusFlow)`

---

## Phase 1: Data Model

### Task 1: Define StatusState Types and Serialization Tests

**Files:**
- Create: `src/main/kotlin/StatusState.kt`
- Create: `src/test/kotlin/StatusStateTest.kt`

- [ ] **Step 1: Write the failing serialization tests**

```kotlin
// src/test/kotlin/StatusStateTest.kt
package io.konektis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusStateTest {

    @Test
    fun `DeviceHealth Online round-trips through JSON with type discriminator`() {
        val health = DeviceHealth.Online(lastSeenAt = 1748000000000L, powerW = 1800, extraInfo = "62% SoC")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"online\""), "Expected type discriminator in: $json")
        val decoded = Json.decodeFromString<DeviceHealth>(json)
        assertEquals(health, decoded)
    }

    @Test
    fun `DeviceHealth Offline with null lastSeenAt round-trips through JSON`() {
        val health = DeviceHealth.Offline(lastSeenAt = null, lastError = "Connection refused")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"offline\""), "Expected type discriminator in: $json")
        val decoded = Json.decodeFromString<DeviceHealth>(json)
        assertEquals(health, decoded)
    }

    @Test
    fun `DeviceHealth Offline with lastSeenAt round-trips through JSON`() {
        val health = DeviceHealth.Offline(lastSeenAt = 1748000000000L, lastError = "Timeout")
        assertEquals(health, Json.decodeFromString<DeviceHealth>(Json.encodeToString<DeviceHealth>(health)))
    }

    @Test
    fun `StatusState with mixed online and offline devices round-trips through JSON`() {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800)),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "Connection refused"))
            ),
            totalSolarW = 3400,
            gridW = -800,
            batteryW = 300,
            batteryCharge = 62,
            chargerW = 0,
            heatpumpW = 1200
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
./gradlew test --tests "io.konektis.StatusStateTest" 2>&1 | grep -E "error:|FAILED|PASSED" | head -20
```

Expected: compilation error — `DeviceHealth`, `DeviceStatus`, `StatusState` unresolved.

- [ ] **Step 3: Create StatusState.kt**

```kotlin
// src/main/kotlin/StatusState.kt
package io.konektis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DeviceHealth {
    @Serializable
    @SerialName("online")
    data class Online(
        val lastSeenAt: Long,          // epoch milliseconds
        val powerW: Int,
        val extraInfo: String? = null  // e.g. "62% SoC" for battery
    ) : DeviceHealth()

    @Serializable
    @SerialName("offline")
    data class Offline(
        val lastSeenAt: Long? = null,  // null if device was never reached
        val lastError: String? = null
    ) : DeviceHealth()
}

@Serializable
data class DeviceStatus(
    val name: String,
    val health: DeviceHealth
)

@Serializable
data class StatusState(
    val devices: List<DeviceStatus>,
    val totalSolarW: Int?,
    val gridW: Int?,
    val batteryW: Int?,
    val batteryCharge: Int?,
    val chargerW: Int?,
    val heatpumpW: Int?
)
```

- [ ] **Step 4: Run tests — expect all 4 to pass**

```bash
./gradlew test --tests "io.konektis.StatusStateTest"
```

Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/StatusState.kt src/test/kotlin/StatusStateTest.kt
git commit -m "feat: add DeviceHealth sealed class, DeviceStatus, StatusState with serialization"
```

---

## Phase 2: DataCollector Health Tracking

### Task 2: Add Per-Device Health Tracking to DataCollector

**Files:**
- Modify: `src/main/kotlin/DataCollector.kt`
- Create: `src/test/kotlin/DataCollectorHealthTest.kt`

- [ ] **Step 1: Write the failing health tracking tests**

```kotlin
// src/test/kotlin/DataCollectorHealthTest.kt
package io.konektis

import io.konektis.devices.DeviceUpdate
import io.konektis.devices.Volt
import io.konektis.devices.Watt
import io.konektis.devices.World
import io.konektis.devices.battery.Battery
import io.konektis.devices.charger.Charger
import io.konektis.devices.grid.Grid
import io.konektis.devices.grid.GridProperties
import io.konektis.devices.grid.GridState
import io.konektis.devices.smartConsumers.SmartConsumer
import io.konektis.devices.solar.Solar
import io.konektis.devices.solar.SolarState
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import io.konektis.config.GridType
import io.konektis.config.GridProperties as ConfigGridProperties

private fun makeWorld(
    solar: Map<String, Solar> = emptyMap(),
    chargers: Map<String, Charger> = emptyMap(),
    batteries: Map<String, Battery> = emptyMap(),
    smartConsumers: Map<String, SmartConsumer> = emptyMap(),
    grid: Grid = mockk<Grid>().also {
        coEvery { it.update() } just runs
        coEvery { it.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            GridState(Watt(0), Volt(230u))
        )
    }
): World = World(grid, chargers, solar, smartConsumers, batteries)

class DataCollectorHealthTest {

    @Test
    fun `refresh records Offline when device update throws`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } throws Exception("Connection refused")
        coEvery { solar.getState() } returns null

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        val status = collector.statusStateFlow.value
        assertNotNull(status)
        val solarStatus = status.devices.first { it.name == "Sunny Boy 4" }
        assertTrue(solarStatus.health is DeviceHealth.Offline)
        assertEquals("Connection refused", (solarStatus.health as DeviceHealth.Offline).lastError)
        assertNull((solarStatus.health as DeviceHealth.Offline).lastSeenAt)
    }

    @Test
    fun `refresh records Online with powerW when device update succeeds`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } just runs
        coEvery { solar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(1800))
        )

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        val status = collector.statusStateFlow.value
        assertNotNull(status)
        val solarStatus = status.devices.first { it.name == "Sunny Boy 4" }
        assertTrue(solarStatus.health is DeviceHealth.Online)
        assertEquals(1800, (solarStatus.health as DeviceHealth.Online).powerW)
    }

    @Test
    fun `refresh preserves lastSeenAt from previous Online when device goes offline`() = runTest {
        val solar = mockk<Solar>()
        var callCount = 0
        coEvery { solar.update() } answers {
            callCount++
            if (callCount >= 2) throw Exception("timeout")
        }
        coEvery { solar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(1800))
        )

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))

        // First refresh: device online
        collector.refresh()
        val firstHealth = collector.statusStateFlow.value!!
            .devices.first { it.name == "Sunny Boy 4" }.health as DeviceHealth.Online
        val lastSeen = firstHealth.lastSeenAt

        // Second refresh: device offline
        collector.refresh()
        val offlineHealth = collector.statusStateFlow.value!!
            .devices.first { it.name == "Sunny Boy 4" }.health as DeviceHealth.Offline
        assertEquals(lastSeen, offlineHealth.lastSeenAt)
        assertEquals("timeout", offlineHealth.lastError)
    }

    @Test
    fun `refresh emits null totalSolarW when all solar devices are offline`() = runTest {
        val solar = mockk<Solar>()
        coEvery { solar.update() } throws Exception("timeout")
        coEvery { solar.getState() } returns null

        val collector = DataCollector(1, makeWorld(solar = mapOf("Sunny Boy 4" to solar)))
        collector.refresh()

        assertNull(collector.statusStateFlow.value!!.totalSolarW)
    }

    @Test
    fun `one failed device does not prevent other devices from being polled`() = runTest {
        val failingSolar = mockk<Solar>()
        coEvery { failingSolar.update() } throws Exception("Connection refused")
        coEvery { failingSolar.getState() } returns null

        val workingSolar = mockk<Solar>()
        coEvery { workingSolar.update() } just runs
        coEvery { workingSolar.getState() } returns DeviceUpdate(
            TimeSource.Monotonic.markNow(),
            SolarState(Watt(2000))
        )

        val collector = DataCollector(1, makeWorld(
            solar = mapOf("Solar 1" to failingSolar, "Solar 2" to workingSolar)
        ))
        collector.refresh()

        val statuses = collector.statusStateFlow.value!!.devices
        assertTrue(statuses.first { it.name == "Solar 1" }.health is DeviceHealth.Offline)
        assertTrue(statuses.first { it.name == "Solar 2" }.health is DeviceHealth.Online)
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
./gradlew test --tests "io.konektis.DataCollectorHealthTest" 2>&1 | grep -E "error:|FAILED|PASSED" | head -20
```

Expected: compilation error — `statusStateFlow` not found on `DataCollector`.

- [ ] **Step 3: Replace DataCollector.kt**

```kotlin
// src/main/kotlin/DataCollector.kt
package io.konektis

import io.klogging.Klogging
import io.konektis.devices.World
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DataCollector(threads: Int, val world: World) : Klogging {
    private val workerPool = Executors.newFixedThreadPool(threads)
    private val dispatcher = workerPool.asCoroutineDispatcher()
    private val healthMap = ConcurrentHashMap<String, DeviceHealth>()

    val statusStateFlow = MutableStateFlow<StatusState?>(null)

    suspend fun refresh() {
        withContext(dispatcher) {
            val jobs = mutableListOf(
                async { poll("Grid meter") {
                    world.grid.update()
                    val state = world.grid.getState() ?: throw Exception("Grid meter returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }}
            )
            world.solar.forEach { (name, solar) ->
                jobs.add(async { poll(name) {
                    solar.update()
                    val state = solar.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }})
            }
            world.chargers.forEach { (name, charger) ->
                jobs.add(async { poll(name) {
                    charger.update()
                    val state = charger.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.currentPower.value)
                }})
            }
            world.batteries.forEach { (name, battery) ->
                jobs.add(async { poll(name) {
                    battery.update()
                    val state = battery.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(
                        System.currentTimeMillis(),
                        state.update.power.value,
                        "${state.update.charge.toInt()}% SoC"
                    )
                }})
            }
            world.smartConsumers.forEach { (name, consumer) ->
                jobs.add(async { poll(name) {
                    consumer.update()
                    val state = consumer.getState() ?: throw Exception("$name returned no data")
                    DeviceHealth.Online(System.currentTimeMillis(), state.update.power.value)
                }})
            }

            val statuses = jobs.awaitAll()

            val gridW = (healthMap["Grid meter"] as? DeviceHealth.Online)?.powerW
            val totalSolarW = world.solar.keys
                .mapNotNull { (healthMap[it] as? DeviceHealth.Online)?.powerW }
                .takeIf { it.isNotEmpty() }
                ?.sum()
            val batteryKey = world.batteries.keys.firstOrNull()
            val batteryOnline = batteryKey?.let { healthMap[it] as? DeviceHealth.Online }
            val batteryW = batteryOnline?.powerW
            val batteryCharge = batteryKey
                ?.let { world.batteries[it] }
                ?.takeIf { batteryOnline != null }
                ?.getState()?.update?.charge?.toInt()
            val chargerW = world.chargers.keys.firstOrNull()
                ?.let { (healthMap[it] as? DeviceHealth.Online)?.powerW }
            val heatpumpW = world.smartConsumers.keys.firstOrNull()
                ?.let { (healthMap[it] as? DeviceHealth.Online)?.powerW }

            statusStateFlow.value = StatusState(
                devices = statuses,
                totalSolarW = totalSolarW,
                gridW = gridW,
                batteryW = batteryW,
                batteryCharge = batteryCharge,
                chargerW = chargerW,
                heatpumpW = heatpumpW
            )
        }
    }

    private fun previousLastSeen(name: String): Long? = when (val h = healthMap[name]) {
        is DeviceHealth.Online -> h.lastSeenAt
        is DeviceHealth.Offline -> h.lastSeenAt
        null -> null
    }

    private suspend fun poll(name: String, block: suspend () -> DeviceHealth.Online): DeviceStatus {
        return try {
            val health = block()
            healthMap[name] = health
            DeviceStatus(name, health)
        } catch (e: Exception) {
            val health = DeviceHealth.Offline(previousLastSeen(name), e.message)
            healthMap[name] = health
            DeviceStatus(name, health)
        }
    }
}
```

- [ ] **Step 4: Run the health tests — expect all 5 to pass**

```bash
./gradlew test --tests "io.konektis.DataCollectorHealthTest"
```

Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Run the full test suite to check for regressions**

```bash
./gradlew test 2>&1 | tail -15
```

Expected: same pass/fail count as before this task (hardware integration tests for SMABattery still fail with NoRouteToHostException — that is expected).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/DataCollector.kt src/test/kotlin/DataCollectorHealthTest.kt
git commit -m "feat: add per-device health tracking to DataCollector, emit StatusState flow"
```

---

## Phase 3: Status Page Routes

### Task 3: Create StatusPage Ktor Plugin and Tests

**Files:**
- Create: `src/main/kotlin/StatusPage.kt`
- Create: `src/test/kotlin/StatusPageTest.kt`

- [ ] **Step 1: Write the failing route tests**

```kotlin
// src/test/kotlin/StatusPageTest.kt
package io.konektis

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json

private fun ApplicationTestBuilder.installStatusPage(flow: kotlinx.coroutines.flow.Flow<StatusState?>) {
    application {
        install(WebSockets) {
            pingPeriod = 30.seconds
            timeout = 30.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        configureStatusPage(flow)
    }
}

class StatusPageTest {

    @Test
    fun `GET slash status returns 200 with HTML content type`() = testApplication {
        installStatusPage(emptyFlow())
        val response = client.get("/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.contentType()!!.match(ContentType.Text.Html),
            "Expected text/html but got ${response.contentType()}"
        )
    }

    @Test
    fun `GET slash status response body contains EMS`() = testApplication {
        installStatusPage(emptyFlow())
        val body = client.get("/status").bodyAsText()
        assertTrue(body.contains("EMS"), "Expected 'EMS' in response body")
    }

    @Test
    fun `WS slash status-ws sends StatusState JSON when flow emits`() = testApplication {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800)),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "Connection refused"))
            ),
            totalSolarW = null,
            gridW = -800,
            batteryW = null,
            batteryCharge = null,
            chargerW = null,
            heatpumpW = null
        )
        installStatusPage(flowOf(state))

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/status-ws") {
            val frame = incoming.receive() as Frame.Text
            val received = Json.decodeFromString<StatusState>(frame.readText())
            assertEquals(state, received)
        }
    }

    @Test
    fun `WS slash status-ws skips null values from flow`() = testApplication {
        installStatusPage(flowOf(null))

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/status-ws") {
            // Null value is filtered; no frame should arrive before close
            val result = runCatching { incoming.receive() }
            assertTrue(result.isFailure || result.getOrNull() is Frame.Close)
        }
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
./gradlew test --tests "io.konektis.StatusPageTest" 2>&1 | grep -E "error:|FAILED|PASSED" | head -20
```

Expected: compilation error — `configureStatusPage` unresolved.

- [ ] **Step 3: Create StatusPage.kt**

Note: `configureSockets()` must be called before `configureStatusPage()` in `Application.module()` because `configureSockets()` installs the `WebSockets` Ktor plugin. Tests install it explicitly.

```kotlin
// src/main/kotlin/StatusPage.kt
package io.konektis

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.configureStatusPage(statusFlow: Flow<StatusState?>) {
    routing {
        get("/status") {
            val bytes = object {}::class.java.getResourceAsStream("/status.html")
                ?.readBytes()
                ?: error("status.html not found in classpath")
            call.respondBytes(bytes, ContentType.Text.Html)
        }
        webSocket("/status-ws") {
            statusFlow.filterNotNull().collect { state ->
                send(Json.encodeToString(state))
            }
        }
    }
}
```

- [ ] **Step 4: Run the route tests — expect all 4 to pass**

```bash
./gradlew test --tests "io.konektis.StatusPageTest"
```

Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/StatusPage.kt src/test/kotlin/StatusPageTest.kt
git commit -m "feat: add StatusPage plugin with GET /status and WebSocket /status-ws"
```

---

## Phase 4: Status HTML Page

### Task 4: Create status.html

**Files:**
- Create: `src/main/resources/status.html`

- [ ] **Step 1: Create status.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>EMS Status</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
           background: #111827; color: #e5e7eb; padding: 20px; max-width: 720px; }
    h1 { color: #93c5fd; font-size: 1.2rem; margin-bottom: 16px; }
    .reconnecting { background: #7c2d12; color: #fed7aa; padding: 8px 12px;
                    border-radius: 6px; margin-bottom: 12px; font-size: 0.85rem; display: none; }
    .flow { display: flex; align-items: center; justify-content: center; gap: 14px;
            background: #1f2937; padding: 16px; border-radius: 8px; margin-bottom: 20px;
            flex-wrap: wrap; }
    .flow-item { text-align: center; min-width: 70px; }
    .flow-label { font-size: 0.72rem; color: #9ca3af; margin-bottom: 2px; }
    .flow-value { font-size: 1.1rem; font-weight: bold; }
    .flow-sub { font-size: 0.7rem; color: #6b7280; margin-top: 2px; }
    .flow-house { background: #374151; padding: 8px 14px; border-radius: 6px; }
    .flow-arrow { color: #374151; font-size: 1.3rem; padding-bottom: 14px; }
    .section-label { font-size: 0.7rem; color: #6b7280; text-transform: uppercase;
                     letter-spacing: 0.06em; margin-bottom: 8px; }
    .devices { display: flex; flex-wrap: wrap; gap: 10px; }
    .card { background: #1f2937; border: 1px solid #374151; border-radius: 8px;
            padding: 10px 14px; min-width: 120px; text-align: center; }
    .card.online { border-color: #166534; }
    .card.offline { border-color: #991b1b; }
    .card-name { display: flex; align-items: center; justify-content: center; gap: 5px;
                 font-size: 0.78rem; font-weight: 600; margin-bottom: 5px; }
    .dot { font-size: 0.55rem; }
    .dot.online { color: #4ade80; }
    .dot.offline { color: #f87171; }
    .card-power { font-size: 1rem; font-weight: bold; color: #e5e7eb; }
    .card-extra { font-size: 0.68rem; color: #9ca3af; margin-top: 2px; }
    .card-offline { font-size: 0.8rem; color: #f87171; }
    .footer { margin-top: 14px; font-size: 0.68rem; color: #4b5563;
              display: flex; justify-content: space-between; }
  </style>
</head>
<body>
  <h1>⚡ Energy Management System</h1>
  <div class="reconnecting" id="reconnecting">⚠ Reconnecting…</div>

  <div class="flow">
    <div class="flow-item">
      <div class="flow-label">☀ Solar</div>
      <div class="flow-value" id="solar-total" style="color:#fbbf24">—</div>
    </div>
    <div class="flow-arrow">→</div>
    <div class="flow-item flow-house">
      <div style="font-size:1.3rem">🏠</div>
      <div class="flow-label">House</div>
    </div>
    <div class="flow-arrow">→</div>
    <div class="flow-item">
      <div class="flow-label">⚡ Grid</div>
      <div class="flow-value" id="grid-value">—</div>
      <div class="flow-sub" id="grid-direction"></div>
    </div>
  </div>

  <div class="section-label">Devices</div>
  <div class="devices" id="devices"></div>

  <div class="footer">
    <span>Live · WebSocket</span>
    <span id="last-update">—</span>
  </div>

  <script>
    const DELAYS = [1000, 2000, 4000, 8000, 16000, 30000];
    let attempt = 0;
    let ws;

    function fmt(w) {
      if (w == null) return '—';
      const a = Math.abs(w);
      return a >= 1000 ? (a / 1000).toFixed(1) + ' kW' : a + ' W';
    }

    function ago(ms) {
      if (ms == null) return 'never';
      const s = Math.floor((Date.now() - ms) / 1000);
      if (s < 60) return s + 's ago';
      if (s < 3600) return Math.floor(s / 60) + 'm ago';
      return Math.floor(s / 3600) + 'h ago';
    }

    function render(s) {
      // Solar total
      document.getElementById('solar-total').textContent = fmt(s.totalSolarW);

      // Grid direction
      const gv = document.getElementById('grid-value');
      const gd = document.getElementById('grid-direction');
      if (s.gridW != null) {
        gv.textContent = fmt(s.gridW);
        if (s.gridW < 0) { gv.style.color = '#34d399'; gd.textContent = 'exporting'; }
        else if (s.gridW > 0) { gv.style.color = '#f87171'; gd.textContent = 'importing'; }
        else { gv.style.color = '#e5e7eb'; gd.textContent = 'balanced'; }
      } else {
        gv.textContent = '—'; gv.style.color = '#e5e7eb'; gd.textContent = '';
      }

      // Device cards
      document.getElementById('devices').innerHTML = s.devices.map(d => {
        const on = d.health.type === 'online';
        const body = on
          ? `<div class="card-power">${fmt(d.health.powerW)}</div>`
            + (d.health.extraInfo ? `<div class="card-extra">${d.health.extraInfo}</div>` : '')
          : `<div class="card-offline">offline</div>`
            + `<div class="card-extra">last seen ${ago(d.health.lastSeenAt)}</div>`;
        return `<div class="card ${on ? 'online' : 'offline'}">
          <div class="card-name"><span class="dot ${on ? 'online' : 'offline'}">●</span>${d.name}</div>
          ${body}</div>`;
      }).join('');

      document.getElementById('last-update').textContent =
        'updated ' + new Date().toLocaleTimeString();
    }

    function connect() {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      ws = new WebSocket(proto + '//' + location.host + '/status-ws');
      ws.onopen = () => {
        attempt = 0;
        document.getElementById('reconnecting').style.display = 'none';
      };
      ws.onmessage = e => { try { render(JSON.parse(e.data)); } catch(err) { console.error(err); } };
      ws.onclose = ws.onerror = () => {
        document.getElementById('reconnecting').style.display = 'block';
        setTimeout(connect, DELAYS[Math.min(attempt++, DELAYS.length - 1)]);
      };
    }

    connect();
  </script>
</body>
</html>
```

- [ ] **Step 2: Build to confirm classpath resource is found**

```bash
./gradlew compileKotlin processResources
```

Expected: BUILD SUCCESSFUL. Verify the file is in the build output:

```bash
ls build/resources/main/status.html
```

Expected: file exists.

- [ ] **Step 3: Re-run StatusPageTest to confirm HTML is served**

```bash
./gradlew test --tests "io.konektis.StatusPageTest"
```

Expected: all 4 tests still pass (including "response body contains EMS").

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/status.html
git commit -m "feat: add self-contained status.html with real-time WebSocket rendering"
```

---

## Phase 5: Wiring

### Task 5: Wire StatusPage into Application.kt

**Files:**
- Modify: `src/main/kotlin/Application.kt`

- [ ] **Step 1: Update Application.kt**

The current `module()` signature is:
```kotlin
fun Application.module(emsStateFlow: Flow<EMSState>, wsConfig: WebSocketConfig)
```

Replace the entire file with:

```kotlin
package io.konektis

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource
import io.klogging.Klogging
import io.klogging.Level
import io.klogging.config.loggingConfiguration
import io.klogging.rendering.RENDER_SIMPLE
import io.klogging.sending.STDOUT
import io.konektis.config.loadConfig
import io.konektis.config.WebSocketConfig
import io.konektis.devices.World
import io.konektis.di.AppComponent
import io.konektis.di.create
import io.konektis.ems.EMSState
import io.konektis.ems.EnergyManager
import io.ktor.server.application.*
import io.konektis.ocpp.configureOcppServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

suspend fun main(args: Array<String>) {
    Main().main(args)
}

class Main : Klogging {
    suspend fun main(args: Array<String>) {
        loggingConfiguration {
            sink("stdout", RENDER_SIMPLE, STDOUT)
            logging {
                fromLoggerBase("io.konektis")
                fromMinLevel(Level.TRACE) {
                    toSink("stdout")
                }
            }
        }

        val config = loadConfig("/config.yaml")

        logger.info(config)

        val component = AppComponent::class.create(config)

        val dataCollector = component.dataCollector
        val energyManager = component.energyManager

        coroutineScope {
            launch {
                while (true) {
                    dataCollector.refresh()
                    delay(5000)
                }
            }
            launch { energyManager.run() }
            launch {
                val server = embeddedServer(Netty, port = 8080) {
                    module(energyManager.emsStateFlow, config.websocket, dataCollector.statusStateFlow)
                }
                server.start(wait = true)
            }
        }
    }
}

fun Application.module(
    emsStateFlow: Flow<EMSState>,
    wsConfig: WebSocketConfig,
    statusFlow: Flow<StatusState?>
) {
    configureSecurity()
    configureAdministration()
    configureSockets(emsStateFlow, wsConfig)
    configureStatusPage(statusFlow)   // must come after configureSockets (WebSockets plugin already installed)
    configureOcppServer()
    configureDatabases()
    configureMonitoring()
    configureHTTP()
    configureRouting()
}
```

- [ ] **Step 2: Build and run all tests**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Same test results as before (hardware integration tests still fail with NoRouteToHostException — expected).

- [ ] **Step 3: Verify fat jar builds**

```bash
./gradlew shadowJar && ls -lh build/libs/
```

Expected: `ems-server-all.jar` present.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/Application.kt
git commit -m "feat: wire status page into Application, pass DataCollector statusStateFlow"
```
