# Charger Car-Connection Flow + Control Wiring — Design

**Date:** 2026-06-04
**Status:** Approved (brainstorming)
**Scope:** Server (Kotlin/Ktor) + Android app. The companion i18n work is a separate spec
(`2026-06-04-app-i18n-dutch-design.md`).

## Problem

The app's Charger tab always shows a "Start charging" button and the power-allocation controls,
even when no car is plugged in. It should instead reflect what the charger actually sees:

- **No car connected** → just an indicator.
- **Car connected, not charging** → the current screen (Start button + power-allocation controls).
- **Car charging** → power-allocation controls remain editable, but show a **Stop** button.

Two gaps make this more than a cosmetic app change:

1. **Car-connection status never reaches the app.** `ChargerScreen` reads `StatusState.chargerW`
   (over `/status-ws`) and infers "charging" from `chargerW > 0`. There is no "is a car plugged in"
   signal. The server *does* track real OCPP connector status (`ChargePointStatus`) in
   `OcppService`, but only exposes it on `/ocpp-ui` — not on the app protocol.
2. **Charging control is a no-op server-side.** The app sends `ClientMessage.SetCharging` on `/ws`,
   but the `/ws` handler only implements `Authenticate` and `SetMode`; `SetCharging` falls into an
   `else` branch that does nothing. The Solar/Fixed toggle and Start/Stop button therefore never
   reach the charger today — only the global AUTO/MANUAL `SetMode` does.

This feature closes both gaps and drives the three-state UI from real data.

## Decisions

- **Charger is OCPP.** Connection status comes from OCPP connector status. Non-OCPP (Webasto)
  reports `Unknown` and the app degrades gracefully (keeps showing controls).
- **Independent charger control.** The charger's Solar/Fixed/Stop intent is honoured every tick
  regardless of the global AUTO/MANUAL master switch. (In MANUAL the EMS still hands battery + heat
  pump back to their own logic; only the charger leg is steered.)
- **No-car screen = indicator only.** Controls are hidden until a car is connected.
- **MANUAL + ExcessPower = best effort.** In MANUAL the battery does its own thing and may compete
  with the charger for solar surplus. Stop/Fixed are unambiguous; ExcessPower is allowed but flagged
  in the UI with a short note rather than disabled.

## Architecture

### 1. Surface car-connection status (server → app)

`ChargerConnection` enum, app-facing, three meaningful states plus a fallback:

| ChargerConnection | OCPP `ChargePointStatus` source |
|-------------------|---------------------------------|
| `NotConnected`    | `Available`, `Reserved`, `Unavailable` |
| `Connected`       | `Preparing`, `SuspendedEV`, `SuspendedEVSE`, `Finishing` |
| `Charging`        | `Charging` |
| `Unknown`         | `Faulted`, or no OCPP data / non-OCPP charger |

- Add `connection: ChargerConnection` to `devices/charger/Charger.kt`'s `ChargerState`.
- `OcppService` gains `connectorStatus(chargePointId, connectorId): ChargePointStatus?` reading the
  tracked `ConnectorState.status`.
- `OcppCharger.getState()` maps the connector status → `ChargerConnection`. `Webasto.getState()`
  returns `ChargerConnection.Unknown`.
- Surface into `StatusState` (both `src/main/kotlin/StatusState.kt` and the app's
  `data/model/StatusState.kt`) as a new nullable `chargerConnection: String?` field
  (serialised enum name; nullable for forward/backward compatibility and non-OCPP setups).
  `DataCollector` captures the charger's `connection` alongside `chargerW` when polling the charger
  and sets it on the emitted `StatusState`.

`/status-ws` stays app-unauthenticated (edge-protected remotely); connection status is not sensitive.

### 2. Wire charging control (server)

Mirror the existing `mode` / `modeFlow` machinery in `EnergyManager`:

- `@Volatile var chargerControl: ChargingState = ChargingWithExcessPower` (private set).
- `fun setCharging(state: ChargingState)` — updates the field and `chargingStateFlow`.
- `val chargingStateFlow = MutableStateFlow<ChargingState>(ChargingWithExcessPower)`.

`Sockets.kt` `/ws`:
- Handle `ClientMessage.SetCharging` (authenticated) → `energyManager.setCharging(state)` and echo
  `Message.ChargingStateUpdate(state)`.
- On successful auth, send the current `chargingStateFlow.value` (like `ModeUpdate` is sent on auth).
- A collector pushes `ChargingStateUpdate` whenever `chargingStateFlow` changes (mirrors `modeJob`).

New message type in **both** `src/main/kotlin/Messages.kt` and
`android/.../data/model/WsMessage.kt`, with matching `@SerialName("ChargingStateUpdate")`:

```kotlin
@Serializable @SerialName("ChargingStateUpdate")
data class ChargingStateUpdate(val chargingState: ChargingState) : Message()
```

### 3. Apply the charger intent every tick (`EnergyManager.tick`)

Resolve the charger setpoint from `chargerControl` independent of global mode:

```
chargerOverrideAmps =
    NotCharging           -> 0
    ChargingWithMaxPower(w)-> clamp(w / 230, 0, maxAmps)
    ChargingWithExcessPower-> null   // strategy computes surplus
```

- **AUTO, full data (tier 1):** pass `chargerOverrideAmps` into the strategy via a new
  `WorldSnapshot.chargerOverrideAmps: Int?` field. When non-null the strategy uses it directly for
  the charger leg (skipping the surplus calc) and projects the battery target from that setpoint, so
  the deadbeat battery math stays exact. When null the strategy computes the charger leg from surplus
  exactly as today. `applyDecisions` applies charger + battery + heat pump.
- **AUTO, degraded/blind (tiers 2–3):** unchanged for battery/heat pump. The charger setpoint is
  still applied for Stop/Fixed (no surplus data needed); for ExcessPower without full data the
  charger is left as-is (cannot compute surplus).
- **MANUAL:** battery + heat pump remain released (unchanged `releaseAll()` on transition). The
  charger leg is still applied: Stop → 0 A, Fixed → clamped amps, ExcessPower → the strategy's
  charger-leg result applied **alone** (battery/heat pump decisions ignored). This is the
  best-effort case noted above.

`SetChargingProfile` (existing `OcppCharger.setMaxChargerPower` path) carries all setpoints,
including 0 A to pause. `RemoteStop` is **not** used (pausing at 0 A is sufficient and keeps the
transaction); this matches the existing control path and the SmartCharging capability gate already
in `OcppCharger`.

### 4. App — three-state Charger tab

`DashboardViewModel` exposes:
- `chargerConnection` derived from `StatusState.chargerConnection`.
- `chargingState: StateFlow<ChargingState?>` from `ControlWsClient` (new, fed by
  `ChargingStateUpdate`; mirrors the existing `mode` flow).

A **pure** mapping function (unit-testable, no Compose) decides the screen:

```kotlin
enum class ChargerUiState { NO_CAR, CONNECTED_IDLE, CHARGING, CONTROLS_FALLBACK }

fun chargerUiState(connection: ChargerConnection?): ChargerUiState = when (connection) {
    ChargerConnection.NotConnected -> NO_CAR
    ChargerConnection.Connected    -> CONNECTED_IDLE
    ChargerConnection.Charging     -> CHARGING
    null, ChargerConnection.Unknown -> CONTROLS_FALLBACK
}
```

`ChargerScreen`:
- `NO_CAR` → `StatusHero` with a plug/idle icon and "No car connected"; **no** controls.
- `CONNECTED_IDLE` → mode toggle + slider + **START** (current screen).
- `CHARGING` → mode toggle + slider (editable) + **STOP**.
- `CONTROLS_FALLBACK` → show controls (today's behaviour) so status gaps never hide them.
- The Solar/Fixed toggle is initialised from the server's authoritative `chargingState` rather than
  local-only state; a short note appears under the toggle when global mode is MANUAL and ExcessPower
  is selected.

`ControlWsClient` handles `ChargingStateUpdate` → updates `chargingState` StateFlow; reset to `null`
on disconnect/unauthorized (same lifecycle as `mode`).

## Data flow

```
OCPP charger ──StatusNotification──▶ OcppService.ConnectorState.status
                                            │
                  OcppCharger.getState() maps → ChargerConnection
                                            │
DataCollector ───────────────────────────▶ StatusState.chargerConnection ──/status-ws──▶ app
                                                                                          │
                                                                          chargerUiState(connection)

app ──SetCharging──/ws──▶ EnergyManager.setCharging ──▶ chargerControl (@Volatile)
                                            │                    │
                          ChargingStateUpdate echoed back        ▼
                                                          tick(): resolve amps → strategy/charger
```

## Error handling / edge cases

- **Non-OCPP / no status:** `Unknown` → app shows controls (no regression).
- **SmartCharging unsupported:** `setMaxChargerPower` is already a logged no-op; Stop/Fixed won't
  take effect on such a charger. Out of scope to add RemoteStop fallback now (the live charger
  supports SmartCharging).
- **Blind/degraded ticks:** ExcessPower charger setpoint is held (not forced to 0) to avoid dropping
  a live charge on a transient data gap; Stop/Fixed still applied.
- **Backward compatibility:** `chargerConnection` and `ChargingStateUpdate` are additive; older
  clients ignore the unknown fields/messages (kotlinx-serialization `ignoreUnknownKeys` already in
  use on the app).

## Testing

**Server**
- `ChargePointStatus → ChargerConnection` mapping (table-driven).
- `EnergyManager.tick` for each `chargerControl × mode`:
  - Stop → charger 0 A in both modes.
  - Fixed(W) → clamped amps in both modes.
  - ExcessPower + AUTO → surplus amps, battery soaks remainder (existing cascade still correct).
  - ExcessPower + MANUAL → charger gets surplus amps, battery/heat pump untouched.
- Strategy `chargerOverrideAmps`: non-null override used verbatim and battery projected from it;
  null → surplus path unchanged.

**App**
- `chargerUiState(connection)` mapping.
- `ControlWsClient` updates `chargingState` on `ChargingStateUpdate` and clears on disconnect.

## Out of scope

- RemoteStart/Stop transaction management (pausing at 0 A suffices).
- Multi-charger control (single charger assumed, consistent with current code).
- Translations (separate i18n spec).
