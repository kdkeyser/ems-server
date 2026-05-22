# Android EMS Companion App — Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A Kotlin/Compose Android app that connects to the EMS server WebSocket API to display real-time energy topology and control the car charger.

**Architecture:** Single-module Android app (`android/:app`) in the same repo. Ktor client for WebSockets, kotlin-inject for DI (same framework as the server), DataStore Preferences for settings, Compose + Material3 for UI.

**Tech stack:** Kotlin, Jetpack Compose, Material3, Ktor client (OkHttp engine), kotlinx.serialization, kotlin-inject (KSP), DataStore Preferences, Compose Navigation, kotlinx-coroutines-test.

---

## Project layout

The app lives at `android/` in the repo root — a self-contained Gradle project with a single `:app` module. The server's build files are untouched.

```
android/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/kotlin/io/konektis/ems/
        ├── EmsApplication.kt
        ├── di/
        │   ├── AppComponent.kt
        │   ├── AppModule.kt
        │   └── AppScope.kt
        ├── data/
        │   ├── model/
        │   │   ├── StatusState.kt        (copy of server model)
        │   │   └── WsMessage.kt          (copy of server Messages.kt)
        │   ├── ws/
        │   │   ├── StatusWsClient.kt
        │   │   └── ControlWsClient.kt
        │   └── settings/
        │       └── SettingsRepository.kt
        └── ui/
            ├── MainActivity.kt
            ├── NavHost.kt
            ├── dashboard/
            │   ├── DashboardScreen.kt
            │   └── DashboardViewModel.kt
            ├── settings/
            │   ├── SettingsScreen.kt
            │   └── SettingsViewModel.kt
            └── components/
                ├── TopologyView.kt
                └── DeviceNode.kt
```

---

## Server API

Two WebSocket endpoints on the EMS server (default port 8080):

### `/status-ws` — read-only, no authentication
Streams `StatusState` JSON every ~5 seconds:
```json
{
  "devices": [
    { "name": "Grid meter", "health": { "type": "online", "lastSeenAt": 1748000000000, "powerW": -800 }, "category": "grid" },
    { "name": "Sunny Boy", "health": { "type": "offline", "lastError": "timeout" }, "category": "solar" }
  ],
  "totalSolarW": null,
  "gridW": -800,
  "batteryW": 200,
  "batteryCharge": 62,
  "chargerW": 0,
  "heatpumpW": 1200
}
```
Power sign convention: **negative = producing/exporting, positive = consuming/importing**. Solar is the exception — its `powerW` is positive when producing.

### `/ws` — bidirectional, username + password authentication
Client sends `Authenticate` first, then `SetCharging`. Server responds with `Authenticated` / `Unauthorized` and streams `PowerUsageUpdate`.

```json
// Client → Server
{ "type": "io.konektis.ClientMessage.Authenticate", "username": "user", "password": "pass" }
{ "type": "io.konektis.ClientMessage.SetCharging", "chargingState": { "type": "io.konektis.ChargingState.NotCharging" } }
{ "type": "io.konektis.ClientMessage.SetCharging", "chargingState": { "type": "io.konektis.ChargingState.ChargingWithExcessPower" } }
{ "type": "io.konektis.ClientMessage.SetCharging", "chargingState": { "type": "io.konektis.ChargingState.ChargingWithMaxPower", "maxPower": 7400 } }
```

---

## Data layer

### `data/model/StatusState.kt`
Exact copy of the server's `StatusState.kt` — `DeviceHealth` sealed class with `@SerialName("online"/"offline")` discriminators, `DeviceStatus(name, health, category)`, `StatusState`. No translation layer; the Ktor client deserializes wire JSON directly into these classes.

### `data/model/WsMessage.kt`
Exact copy of the server's `Messages.kt` — `ChargingState`, `Message`, `ClientMessage`, `Update`, `Devices`.

### `data/settings/SettingsRepository.kt`
Wraps DataStore Preferences. Exposes:
- `val settingsFlow: Flow<Settings>` — emits on every change
- `suspend fun save(settings: Settings)`

`Settings` is a plain data class: `serverUrl: String`, `username: String`, `password: String`. The URL is stored without a scheme; clients prepend `ws://` or `wss://`.

### `data/ws/StatusWsClient.kt`
- Constructor: `SettingsRepository`, `HttpClient`
- Exposes `fun statusFlow(): Flow<StatusState>` — a `callbackFlow` that:
  1. Collects `settingsRepository.settingsFlow`
  2. For each `Settings` emission, opens a Ktor WebSocket session to `ws://{serverUrl}/status-ws`
  3. Collects text frames, deserializes, emits `StatusState`
  4. On any exception or close: waits with exponential backoff, then reconnects
- Backoff sequence: `[1, 2, 4, 8, 16, 30]` seconds; resets to 1s on successful connect
- Settings change cancels the current session immediately and reconnects with new URL

### `data/ws/ControlWsClient.kt`
- Constructor: `SettingsRepository`, `HttpClient`
- Exposes:
  - `val connectionState: StateFlow<ControlState>` — `Connecting | Authenticated | Unauthenticated | Disconnected(error: String?)`
  - `suspend fun send(command: ClientMessage)` — throws `IllegalStateException` if not `Authenticated`
- On connect: immediately sends `ClientMessage.Authenticate(username, password)`
- `Unauthorized` response → state = `Unauthenticated`, no retry
- Any other disconnect → exponential backoff retry (same sequence as above)
- Settings change → cancel + reconnect

---

## Dependency injection

Same pattern as the server. One scope annotation:

```kotlin
@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class AppScope
```

`AppModule` provides all singletons:

```kotlin
interface AppModule {
    @AppScope @Provides
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) { install(WebSockets) }

    @AppScope @Provides
    fun provideSettingsRepository(context: Context): SettingsRepository =
        SettingsRepository(context)

    @AppScope @Provides
    fun provideStatusWsClient(settings: SettingsRepository, client: HttpClient): StatusWsClient =
        StatusWsClient(settings, client)

    @AppScope @Provides
    fun provideControlWsClient(settings: SettingsRepository, client: HttpClient): ControlWsClient =
        ControlWsClient(settings, client)
}
```

`AppComponent` takes `Context` (needed for DataStore):

```kotlin
@AppScope @Component
abstract class AppComponent(@get:Provides val context: Context) : AppModule {
    abstract val statusWsClient: StatusWsClient
    abstract val controlWsClient: ControlWsClient
    abstract val settingsRepository: SettingsRepository
    companion object
}
```

`EmsApplication.onCreate()` calls `AppComponent::class.create(this)` and holds the result. Screens access it via `(LocalContext.current.applicationContext as EmsApplication).component`.

ViewModels are not scoped — created via `viewModelFactory { DashboardViewModel(component.statusWsClient, component.controlWsClient) }` in the nav graph.

---

## UI

### Navigation
Two destinations: `Dashboard` (start) and `Settings`. The settings gear icon in the dashboard top bar pushes `Settings` onto the back stack; the system back button or back arrow returns to `Dashboard`.

### Dashboard screen

**Top app bar:** title "EMS", settings gear icon on the right.

**Connection status pill:** a small rounded chip directly below the bar.
- Green: "Connected" (status WS receiving data)
- Amber: "Connecting…" (backoff in progress)
- Red: "Disconnected — \<error\>" (no active connection)

**Topology view (`TopologyView.kt`):**
A `Box` with `Modifier.fillMaxWidth().aspectRatio(5f/3f)`. A `Canvas` fills the box and draws the lines. Device nodes are `DeviceNode` composables positioned with `BiasAlignment` (percentage position converted to -1..+1 bias).

Node positions (matching the web status page):
| Node     | left % | top % | xBias | yBias |
|----------|--------|-------|-------|-------|
| Solar    | 50     | 14    |  0.0  | -0.72 |
| Grid     | 12     | 50    | -0.76 |  0.0  |
| House    | 50     | 50    |  0.0  |  0.0  |
| Battery  | 88     | 50    |  0.76 |  0.0  |
| Charger  | 30     | 84    | -0.40 |  0.68 |
| HeatPump | 70     | 84    |  0.40 |  0.68 |

Line colours:
- Green (`#34d399`): producing (solar > 0, grid < 0, battery < 0)
- Red (`#f87171`): consuming (grid > 0, battery > 0, charger > 0, heatpump > 0)
- Grey (`#4b5563`): zero or null

Each `DeviceNode` shows: emoji icon, device type label, power value (coloured), row of status dots (one `●` per physical device of that category, green if online, red if offline).

Nodes for categories absent from `StatusState.devices` are hidden.

**Charger control card:**
Shown only when `ControlWsClient.connectionState` is `Authenticated`. Three `SegmentedButton`s: **Off** | **Solar surplus** | **Max power**. Selecting "Max power" reveals a numeric text field for the watt limit (default 7400 W). Tapping any button calls `DashboardViewModel.setCharging(ChargingState)`.

When `ControlState` is `Unauthenticated`, shows a text message: "Charger control unavailable — check credentials in Settings."

### Settings screen
Three `OutlinedTextField`s:
- **Server address** — placeholder `192.168.1.100:8080`, no scheme
- **Username**
- **Password** — `visualTransformation = PasswordVisualTransformation()`

**Save** `Button` — calls `SettingsViewModel.save()`, writes to DataStore, navigates back. Both WS clients pick up the new settings automatically.

---

## Error handling & reconnection

Both clients use the same backoff sequence: `[1, 2, 4, 8, 16, 30]` seconds, capped at 30s thereafter. Resets to 1s on successful connect.

`StatusWsClient`: any exception → set `Disconnected`, schedule retry. Last received `StatusState` remains in `DashboardViewModel.statusState` so the screen stays populated; the connection pill signals staleness.

`ControlWsClient`: `Unauthorized` → `Unauthenticated`, no retry. Any other disconnect → backoff retry.

Settings change: both clients cancel current session and reconnect immediately (no backoff delay).

Background / process kill: WS flows are collected in `viewModelScope`. On process kill and foreground return, `EmsApplication` recreates the component and the ViewModel reconnects from scratch.

---

## Testing

All unit tests, no instrumented tests in scope.

**Model serialization** (`StatusStateTest`, `WsMessageTest`): round-trip `DeviceHealth.Online`, `DeviceHealth.Offline`, `StatusState`, `ChargingState` variants through `Json.encodeToString` / `decodeFromString`.

**ViewModel tests** (`DashboardViewModelTest`, `SettingsViewModelTest`): in-memory fakes for `StatusWsClient`, `ControlWsClient`, `SettingsRepository`. Uses `kotlinx-coroutines-test` `runTest`. Covers:
- Status updates flow through to `statusState`
- Charger control card visibility driven by `ControlState`
- `setCharging()` forwards correct `ClientMessage`
- Settings save triggers reconnect

**`SettingsRepository` tests**: in-memory DataStore via `PreferenceDataStoreFactory.create(scope, produceFile = { tempFile })`. Verifies save/load round-trip and flow emission on change.

---

## Dependencies (app/build.gradle.kts)

```kotlin
// Compose + Material3
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
implementation("androidx.navigation:navigation-compose")

// Ktor client
implementation("io.ktor:ktor-client-okhttp")
implementation("io.ktor:ktor-client-websockets")
implementation("io.ktor:ktor-serialization-kotlinx-json")

// kotlinx.serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

// DataStore
implementation("androidx.datastore:datastore-preferences")

// kotlin-inject
implementation("me.tatarka.inject:kotlin-inject-runtime")
ksp("me.tatarka.inject:kotlin-inject-compiler-ksp")

// Testing
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
testImplementation("kotlin.test:kotlin-test")
```
