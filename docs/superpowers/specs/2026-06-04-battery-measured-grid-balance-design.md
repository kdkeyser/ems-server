# Battery Balances Measured Grid (drop charger setpoint feed-forward) — Design

**Date:** 2026-06-04
**Status:** Approved (brainstorming)
**Scope:** Server only (`SurplusPriorityStrategy`). Independent of, and sequenced before, the charger-control
unification (`2026-06-04-charger-control-unification-design.md`).

## Problem

`SurplusPriorityStrategy.decide()` sets the battery target from a *projected* grid that assumes the
charger will draw its commanded setpoint:

```kotlin
val projectedGrid = gridPower + chargerConsumption - chargerPower   // chargerConsumption = chargerAmps * 230 (SETPOINT)
val batteryTarget = batteryTarget(batteryPower, projectedGrid)
```

The `(chargerConsumption − chargerPower)` term assumes the car actually consumes the commanded
current. When it doesn't — car charging slower than commanded, or battery full so it draws ~0 — that
term is a **constant** offset the battery loop faithfully cancels, parking the grid at a **persistent
export** of `(setpoint − measured)`.

Worked example (car capped at 1 kW, 3 kW solar surplus, charger commanded ~3 kW): the battery stays
idle and the system exports ~2 kW **continuously** — in both fixed and solar-surplus modes. This is a
steady-state bug, not a transient.

(The "battery charges while the grid imports" transient that the projection was originally added to
suppress turned out to be a battery-setpoint **sign** bug, since fixed — not a reason to keep the
feed-forward.)

## Decision

**Balance the battery on the *measured* grid; remove the charger setpoint feed-forward.** The battery
always drives the measured grid to zero, so the charger's *actual* draw (whatever the car takes) is
what gets balanced. The charger's commanded current is realised over a tick or two, and the meter
reflects reality each tick.

## Architecture

In `SurplusPriorityStrategy.decide()`:

- Keep the charger-leg amps computation (surplus or `chargerOverrideAmps`) and the heat-pump leg —
  both are already based on measured values.
- Replace the battery leg:

```kotlin
// was: val projectedGrid = snapshot.gridPower.value + chargerConsumption - snapshot.chargerPower.value
//      val batteryTarget = batteryTarget(snapshot.batteryPower.value, projectedGrid)
val batteryTarget = batteryTarget(snapshot.batteryPower.value, snapshot.gridPower.value)
```

This makes the battery leg of `decide()` identical to `decideDegraded()` — the battery balances the
measured grid regardless of tier. The deadbeat on the battery's *own* power (gain 1, within
deadband) is unchanged; only the unreliable charger feed-forward is dropped. `chargerConsumption`
becomes unused and is removed.

### Convergence (why this is correct)

Per-tick the battery cancels the measured grid imbalance; the charger ramps independently. On an
abrupt surplus increase both briefly ramp and the system converges in ~1–2 ticks (~5–10 s) to
charger-priority (charger takes the surplus, battery idle). When the car under-draws, the battery
soaks the surplus the car won't take and the grid settles at ~0 — no export. This holds in both
solar-surplus and fixed-charger modes.

## Testing

`SurplusPriorityStrategyTest` (single-tick `decide()` assertions):

- **Existing battery-target assertions change** from the projected values to `batteryPower −
  gridPower` (within deadband). Update them, e.g. "large solar surplus" now expects the battery to
  balance the *measured* −2000 W export (target +2000) rather than the projected +90.
- **New: car under-draw produces no export.** Fixed override at high amps but `chargerPower`
  (measured) low, grid exporting → battery target balances the *measured* grid, not the setpoint
  (regression guard for the bug above).
- The charger-leg (`chargerMaxAmps`) and heat-pump assertions are unchanged.
- `EnergyManagerTest` tier-1 cases that assert a battery `setChargingPower` value are updated to the
  measured-grid result.

## Out of scope

- The charger-control model / persistence / UI changes (separate spec).
- Any change to the heat-pump or charger-surplus legs.
