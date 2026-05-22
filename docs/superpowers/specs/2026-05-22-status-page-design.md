# EMS Status Page Design

**Date:** 2026-05-22  
**Status:** Approved

---

## Overview

Add a real-time HTML status page served by the EMS server that shows the connectivity health of every managed device and the current energy distribution. The page updates live via WebSocket.

---

## Data Model

### DeviceHealth (sealed class)

```kotlin
sealed class DeviceHealth {
    data class Online(
        val lastSeenAt: Long,      // epoch milliseconds
        val powerW: Int,
        val extraInfo: String? = null  // e.g. "62% SoC" for battery
    ) : DeviceHealth()

    data class Offline(
        val lastSeenAt: Long?,     // null if device was never reached
        val lastError: String?
    ) : DeviceHealth()
}
```

Power readings and extra info only exist in the `Online` case — the type system enforces that these fields are absent when a device is unreachable.

`lastSeenAt` is serialized as epoch milliseconds (`Long`) to avoid a kotlinx-datetime dependency. JavaScript renders it with `new Date(ms).toLocaleTimeString()`.

### DeviceStatus

```kotlin
data class DeviceStatus(
    val name: String,       // display name from config
    val health: DeviceHealth
)
```

### StatusState

```kotlin
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

Aggregate power totals mirror `EMSState` for the flow summary. Per-device readings live in each `DeviceStatus`.

### Example JSON (WebSocket message)

```json
{
  "devices": [
    { "name": "SMA Solar 1",  "health": { "type": "online",  "lastSeenAt": 1748000000000, "powerW": 1800 } },
    { "name": "SMA Solar 2",  "health": { "type": "online",  "lastSeenAt": 1748000000000, "powerW": 1600 } },
    { "name": "P1 Grid meter","health": { "type": "online",  "lastSeenAt": 1748000000000, "powerW": -800 } },
    { "name": "Webasto",      "health": { "type": "offline", "lastSeenAt": 1747999800000, "lastError": "Connection refused" } },
    { "name": "SMA Battery",  "health": { "type": "online",  "lastSeenAt": 1748000000000, "powerW": 300, "extraInfo": "62% SoC" } },
    { "name": "Daikin HP",    "health": { "type": "online",  "lastSeenAt": 1748000000000, "powerW": 1200 } }
  ],
  "totalSolarW": 3400,
  "gridW": -800,
  "batteryW": 300,
  "batteryCharge": 62,
  "chargerW": 0,
  "heatpumpW": 1200
}
```

---

## Architecture

### Data flow

```
DataCollector.refresh()
    ├── world.grid.update()          ─┐
    ├── world.solar[*].update()       │  each wrapped in try/catch
    ├── world.chargers[*].update()    │  independently
    ├── world.batteries[*].update()   │
    └── world.smartConsumers[*].update() ─┘
            ↓
    Build StatusState from device.getState() + health map
            ↓
    statusStateFlow.emit(statusState)
            ↓
    /status-ws WebSocket → browser
```

### Components

**`DataCollector` (modified)**

- Wraps each `device.update()` call in an individual try/catch. One failing device never blocks the others.
- Maintains a `MutableMap<String, DeviceHealth>` keyed by device name.
  - On success: records `Online(lastSeenAt = System.currentTimeMillis(), powerW = ..., extraInfo = ...)`
  - On failure: records `Offline(lastSeenAt = previous?.lastSeenAt, lastError = e.message)`
- After all devices are polled, assembles a `StatusState` from the health map and each device's current `getState()` reading.
- Emits to `val statusStateFlow: MutableStateFlow<StatusState?>` (starts as `null` before the first refresh).

**`src/main/kotlin/StatusPage.kt` (new)**

Ktor plugin function:
```kotlin
fun Application.configureStatusPage(dataCollector: DataCollector)
```

Registers two routes:
- `GET /status` — responds with the contents of `status.html` from classpath resources, `Content-Type: text/html`.
- `WebSocket /status-ws` — no authentication. Collects from `dataCollector.statusStateFlow`, serializes each `StatusState` to JSON, and sends it to every connected client. Skips `null` (pre-first-refresh) values.

**`src/main/resources/status.html` (new)**

Self-contained HTML/CSS/JS file, no build step, no external dependencies.

- On load: opens `ws://<host>/status-ws`.
- On message: deserializes JSON and re-renders:
  - **Power flow bar**: Solar → House → Grid (with export/import sign and colour)
  - **Device cards**: one card per device. Online cards show name, green dot, `powerW` (and `extraInfo` if present). Offline cards show name, red dot, "offline", and "last seen X ago".
- On disconnect: shows a "Reconnecting…" banner, retries with exponential backoff (1 s → 2 s → 4 s, capped at 30 s).
- No authentication required (the endpoint is unauthenticated).

**`Application.kt` (modified)**

Calls `configureStatusPage(dataCollector)` in `Application.module()`.

**`AppModule.kt` (modified)**

`module()` receives `dataCollector` alongside `emsStateFlow` and `wsConfig`.

### What stays unchanged

- `/ws` WebSocket endpoint and `EnergyManager.emsStateFlow` — the mobile app is unaffected.
- All device interfaces and implementations.
- `config.yaml` format — device names are read from the existing `name` fields in `config.devices.*`.

---

## Device Name Mapping

Device names shown on the status page come from the `name` field in `config.yaml` (already present on `Charger`, `Solar`, `HeatPump`, `Battery` config entries). The grid meter has no name field — it uses the fixed display name `"Grid meter"`.

---

## Serialization

`StatusState` and `DeviceHealth` use `@Serializable` (kotlinx.serialization). The sealed class uses the default `type` discriminator:

```kotlin
@Serializable
sealed class DeviceHealth {
    @Serializable @SerialName("online")
    data class Online(val lastSeenAt: Long, val powerW: Int, val extraInfo: String? = null) : DeviceHealth()

    @Serializable @SerialName("offline")
    data class Offline(val lastSeenAt: Long? = null, val lastError: String? = null) : DeviceHealth()
}
```

`Instant` is stored as `Long` (epoch milliseconds) throughout.

---

## Error Handling

- Per-device failures are caught independently. A Modbus timeout on the battery does not affect the solar or grid readings.
- If a device has never been reached (first refresh failed), `Offline.lastSeenAt` is `null` and the UI shows "never connected".
- If `statusStateFlow` has not emitted yet (server just started), `/status-ws` clients wait silently until the first refresh completes.

---

## Testing

- `DataCollector` health tracking: unit-tested by injecting a mock device whose `update()` throws, and asserting the resulting `StatusState` contains an `Offline` entry with the expected error message.
- `StatusPage` routes: integration-tested with Ktor's `testApplication` — verify `GET /status` returns HTML, and that `/status-ws` sends a JSON message after a `StatusState` is emitted.
- `DeviceHealth` serialization: unit-tested round-trip (serialize → deserialize → assert equality).
- The HTML page is manually verified in a browser against the running server.
