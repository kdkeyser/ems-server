# Charger Minimum Current in Solar Mode — Design

**Date:** 2026-06-05
**Status:** Approved (brainstorming)
**Scope:** Server only (`SurplusPriorityStrategy`). Independent of the app UX changes
(`2026-06-05-app-reconnect-and-charging-session-ux-design.md`).

## Problem

In solar-surplus mode the charger leg drops to **0 A** whenever the available surplus is below the
charger's minimum current:

```kotlin
amps < snapshot.chargerMinAmps -> 0
```

A car won't charge at all below its minimum, so as surplus hovers around the minimum the EMS alternates
quickly between "slightly above min" and "off" — chattering the relays in the charger and the car, and
never delivering a stable charge.

## Decision

While a **solar charging session is active**, never allocate the charger below its minimum: floor the
surplus allocation at `chargerMinAmps` instead of dropping to 0. The shortfall below the minimum
(charger min draw − surplus) is absorbed by the battery first (per the measured-grid balance) and then
the grid — explicitly accepted.

This only applies to an **active solar session**: `EnergyManager.effectiveChargerAmps` passes a *null*
`chargerOverrideAmps` (the surplus path) only when `connected && charging && AUTO && mode == SOLAR`.
A stopped/idle charger, Fixed mode, or no car all yield a concrete override (0 or the fixed value), so
the floor never keeps an idle charger drawing.

## Architecture

In `SurplusPriorityStrategy.decide()`, the surplus branch (taken when `chargerOverrideAmps == null`)
becomes a single clamp:

```kotlin
val chargerAmps = snapshot.chargerOverrideAmps?.coerceIn(0, snapshot.chargerMaxAmps)
    ?: (available / 230).coerceIn(snapshot.chargerMinAmps, snapshot.chargerMaxAmps)
```

(`available <= 0` or `available < minAmps*230` both clamp up to `minAmps`; the explicit
`amps < minAmps -> 0` rule is removed.) The battery leg is unchanged — it already balances the measured
grid, so the charger's floored draw is reconciled the same way as any other load. No change to the
heat-pump leg or to `decideDegraded`.

## Testing

`SurplusPriorityStrategyTest` — the surplus-path cases that previously asserted a 0 A charger now assert
the minimum, because the strategy's null-override path means an active solar session:

- `surplus below charger minimum`: `gridPower = -200` → charger `6` (was 0); battery still `+200`.
- `zero solar and importing`: `gridPower = 1500` → charger `6` (held at min during the session); battery
  still `-1500`. Rename to reflect "held at minimum during an active solar session."
- `holds current battery power when the grid is balanced within the deadband`: `gridPower = -40`,
  `batteryPower = 500` → charger `6` (was 0); battery still holds `500`.
- New regression test: an active solar session with surplus below the minimum (e.g. `gridPower = -200`)
  floors the charger at `chargerMinAmps` rather than 0.

Override-path tests (Fixed / Stop, `chargerOverrideAmps` non-null) are unchanged — they clamp to
`[0, maxAmps]`, so 0 is still reachable for a stopped charger. `EnergyManagerTest` is unaffected (its
default control is a solar session; the tier-1 cases use `charger(0)` with surplus ≥ min, unchanged).

## Out of scope

- App changes.
- Fixed-power mode (uses the override path; honours its own value down to 0).
- Whether the shortfall should come strictly from the grid rather than the battery — decided: battery
  first, then grid.
