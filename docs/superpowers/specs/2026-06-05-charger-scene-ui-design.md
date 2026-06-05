# Charger Tab — Charger + Car Scene — Design

**Date:** 2026-06-05
**Status:** Approved (brainstorming)
**Scope:** Android app only — the Charger tab's hero visual. No server or protocol changes.

## Problem

The Charger tab's hero shows a single icon tile (the `EvStation` charger glyph) regardless of whether
a car is plugged in or charging. The user wants the hero to read as a small **scene**: a charger and
a car, where the **car appears when the cable is plugged in**, and a **lightning bolt** shows when the
car is actually charging.

## Decision (from the visual brainstorm)

- **Layout A — side-by-side scene:** the charger (left) and the car (right) face each other with a
  short cable between them.
- **Charger icon:** stock Material `EvStation` (no custom asset). **Car icon:** Material
  `DirectionsCar`. **Charging icon:** Material `Bolt`.
- **No car:** the car is shown as a dim "ghost" (low opacity); no bolt.
- **Connected, idle:** solid car, grey cable, no bolt.
- **Charging:** solid car, amber cable on both sides, and a **pulsing** amber bolt between charger and
  car.
- The scene **replaces only the icon tile** at the top of the Charger card. The value (kW / "Idle" /
  "No car"), the status line with its colored dot, and the existing controls below (Solar/Fixed
  toggle, Fixed-A slider, START/STOP) are unchanged.

## Architecture

The driver is the existing `chargerUiState(connection)` (`NO_CAR` / `CONNECTED_IDLE` / `CHARGING` /
`CONTROLS_FALLBACK`). No new state is introduced.

### 1. Scene spec (pure, testable)

A pure mapping isolates the visual logic for unit testing:

```kotlin
data class ChargerSceneSpec(val showCar: Boolean, val carDimmed: Boolean, val charging: Boolean)

fun chargerSceneSpec(uiState: ChargerUiState): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR          -> ChargerSceneSpec(showCar = true,  carDimmed = true,  charging = false)
    ChargerUiState.CONNECTED_IDLE  -> ChargerSceneSpec(showCar = true,  carDimmed = false, charging = false)
    ChargerUiState.CHARGING        -> ChargerSceneSpec(showCar = true,  carDimmed = false, charging = true)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, charging = false)
}
```

`CONTROLS_FALLBACK` (Unknown/absent connection, e.g. a non-OCPP charger) shows the **charger alone**
— no car, no bolt — matching today's behaviour where car/charging are connection-derived.

### 2. `ChargerScene` composable

A new composable (in `ui/charger/`) renders the scene from a `ChargerSceneSpec`:
- Always: the `EvStation` charger icon (~46.dp).
- If `showCar`: a cable segment + the `DirectionsCar` icon (~46.dp), at alpha ≈ 0.16 when `carDimmed`.
- If `charging`: the cable segments are amber and a `Bolt` icon sits between charger and car,
  **pulsing** via `rememberInfiniteTransition` (alpha ~0.35→1.0, scale ~0.85→1.1, ~1.3 s,
  `RepeatMode.Reverse`). Amber = a local constant (e.g. `Color(0xFFFBBF24)`), consistent with the
  charging value color.
- Icon tint uses the theme foreground; the bolt is amber.

### 3. Charger hero

`StatusHero` is shared with the Heat Pump tab, so it is **not** modified. Instead the Charger tab uses
a small charger-specific hero (either a new `ChargerHero` composable or inline in `ChargerScreen`): a
`Card` containing the `ChargerScene` on top, then the big value text and the status row with its
colored dot — reusing the value/status/online logic already in `ChargerScreen` today:

| uiState | value | status text | dot |
|---|---|---|---|
| NO_CAR | "No car" | "No car connected" | offline |
| CONNECTED_IDLE | "Idle" | "Connected — not charging" | online |
| CHARGING | `formatWatts(chargerW)` (or "Charging" if null) | "Charging" | online |
| CONTROLS_FALLBACK | `formatWatts(chargerW)`/"Idle" | "Charger online"/"Status unavailable" | `chargerW != null` |

`ChargerScreen`'s `when (uiState)` is updated so the `NO_CAR` branch also uses this hero (today it
renders a `StatusHero`); the controls section (auth-gated) below is unchanged.

## Testing

- **`chargerSceneSpec(uiState)`** unit test — the four states map to the expected
  `(showCar, carDimmed, charging)`.
- Existing `chargerUiState` test unchanged.
- Manual: plug/unplug and start/stop on the device — car ghosts in/out; bolt pulses only while charging.
  (The animation/Compose rendering itself isn't unit-tested.)

## Out of scope

- Server/protocol changes; the heat-pump or other tabs' `StatusHero`.
- A custom wall-box charger vector (stock `EvStation` chosen).
- Charging animation beyond the single pulsing bolt.
