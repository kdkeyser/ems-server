# App: Reconnect-on-Resume + Charging-Session UX — Design

**Date:** 2026-06-05
**Status:** Approved (brainstorming)
**Scope:** Android app only. Independent of the server-side charger-minimum-current change
(`2026-06-05-charger-minimum-current-design.md`).

## Problems

1. **Slow reconnect after backgrounding.** Both WS clients (`StatusWsClient`, `ControlWsClient`) run a
   reconnect loop that, after a drop, `delay(WS_BACKOFF[…])` up to **30 s** before retrying. There is
   no lifecycle hook, so returning from the background lands mid-backoff and the app sits disconnected.
2. **No feedback on Start/Stop.** The button label flips only when the *physical* state changes, so a
   press appears to do nothing for several seconds.
3. **Button tracks power, not the session.** The label is derived from `uiState == CHARGING` (the OCPP
   connector status). When a session is running but the car isn't drawing (declined, battery full), the
   connector isn't `Charging`, so the button wrongly shows **START** again.

## Part A — Reconnect immediately on resume

- Add `reconnectNow()` to `StatusWsClient` and `ControlWsClient`, backed by a
  `MutableSharedFlow<Unit>(extraBufferCapacity = 1)` (`reconnectSignal`).
- Drive the connect loop off `combine(settings.settingsFlow, reconnectSignal.onStart { emit(Unit) }) { s, _ -> s }`
  via `flatMapLatest` (`StatusWsClient`, whose `statusFlow` is the cold flow) /
  `collectLatest` (`ControlWsClient`, in its `scope.launch`). A signal cancels the in-flight inner
  block — **including a `delay(WS_BACKOFF…)`** — and restarts the connect with `attempt = 0`, i.e. an
  immediate retry. (`@OptIn(ExperimentalCoroutinesApi)` for `flatMapLatest`.)
- `MainActivity.onStart()` calls `reconnectNow()` on both clients via `app.component`. It fires on every
  foreground transition and reconnects **unconditionally** (no "already connected?" guard) so a socket
  the OS silently killed while backgrounded is replaced too; the only cost is a brief reconnect on
  resume when the socket happened to still be alive.
- No new dependency (uses the Activity lifecycle).

## Part B — Charging session vs. actual power

Two distinct signals, already both available in the app:
- **Session** = `chargerControl.charging` (echoed by the server via `ChargerControlUpdate`).
- **Physical** = the OCPP connector status, surfaced as `chargerUiState` (`NO_CAR` / `CONNECTED_IDLE` /
  `CHARGING` / `CONTROLS_FALLBACK`).

### Button = session

- START when `!sessionActive`, STOP when `sessionActive` — independent of whether the car is drawing. A
  car declining still shows STOP (a session is running).
- **Pending:** on press, set `pending` (true = starting, false = stopping), send
  `ChargerControl(mode = selectedMode, fixedAmps, charging = !sessionActive)`, and show
  **"Starting…/Stopping…"** with the button **disabled** + a small spinner. `pending` clears when the
  echoed `sessionActive` reaches the pressed target (`pending == sessionActive`), or after a **~30 s**
  safety timeout (then it reverts to the normal label).

### Scene = physical (with an "armed" cue)

`ChargerSceneSpec`'s `charging: Boolean` is replaced by `bolt: BoltMode { OFF, ARMED, CHARGING }`, and
`chargerSceneSpec(uiState, sessionActive)` maps:

| uiState | sessionActive | showCar | carDimmed | bolt |
|---|---|---|---|---|
| NO_CAR | any | true | true | OFF |
| CONNECTED_IDLE | false | true | false | OFF |
| CONNECTED_IDLE | true | true | false | ARMED |
| CHARGING | any | true | false | CHARGING |
| CONTROLS_FALLBACK | any | false | false | OFF |

`ChargerScene` renders the bolt: **CHARGING** → pulsing amber bolt + amber cable; **ARMED** → dim
static bolt (≈0.4 alpha, no animation) + grey cable; **OFF** → no bolt.

### Hero value / status

`ChargerHero` gains a `sessionActive` parameter; value/status follow:

| Car | Session | Physically charging | Value | Status |
|---|---|---|---|---|
| none | — | — | "No car" | "No car connected" |
| connected | off | no | "Idle" | "Connected — not charging" |
| connected | on | **yes** | `formatWatts(chargerW)` (or "Charging" if null) | "Charging" |
| connected | on | no | "Ready" | "Session active — car not drawing" |
| (fallback) | — | — | `formatWatts`/"Idle" | "Charger online"/"Status unavailable" |

### New strings (English + Dutch)

`charger_starting` ("Starting…"/"Starten…"), `charger_stopping` ("Stopping…"/"Stoppen…"),
`charger_value_ready` ("Ready"/"Gereed"), `charger_status_session_not_drawing`
("Session active — car not drawing" / "Sessie actief — auto laadt niet").

### Component changes

- `ChargerSceneSpec`: `charging: Boolean` → `bolt: BoltMode`; `chargerSceneSpec(uiState, sessionActive)`.
- `ChargerScene`: render the three bolt modes.
- `ChargerHero(uiState, chargerW, sessionActive)`: the value/status matrix.
- `ChargerScreen`: pass `sessionActive = chargerControl?.charging ?: false` to `ChargerHero`;
  `ChargerControls` drives the button off `control.charging` (drop the `isCharging` param) and owns the
  `pending` state.
- Pure, testable helpers: `chargerSceneSpec(uiState, sessionActive)` and
  `chargerButtonState(sessionActive, pending): ChargerButtonState(labelKind, enabled, stopStyle)` where
  `labelKind ∈ {START, STOP, STARTING, STOPPING}`.

## Testing

- **Unit:** `chargerSceneSpec(uiState, sessionActive)` (the 5-row table); `chargerButtonState` (label/
  enabled/stop-style across `sessionActive × pending`); the pending-clear condition `pending == sessionActive`.
- **Manual (device):** background → resume reconnects immediately; Start shows "Starting…" then STOP
  even if the car declines (scene shows the dim "armed" bolt, status "car not drawing"); Stop shows
  "Stopping…" then START.

## Out of scope

- The server charger-minimum-current floor (separate spec).
- Any server/protocol change — `ChargerControl` already carries `charging`.
