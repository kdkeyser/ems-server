# Charger-Control Unification — Design

**Date:** 2026-06-04
**Status:** Approved (brainstorming)
**Scope:** Server (protocol, control, persistence, DI) + Android app + `/ocpp-ui`. Depends on the
battery measured-grid fix (`2026-06-04-battery-measured-grid-balance-design.md`) landing first.

## Problem

Two distinct concepts are currently tangled across the app and `/ocpp-ui`:

1. **EMS mode** (AUTO/MANUAL) — should decide only *power allocation between devices*.
2. **Charger mode** (Solar surplus / Fixed power) — should control only *the charger*.

Today: the app's Solar/Fixed toggle is independent of EMS mode in ways that don't make sense (solar
surplus is meaningless in MANUAL); the charger control intent (`ChargingState`) is a single
in-memory global that resets on restart; and `/ocpp-ui` exposes low-level per-charger knobs ("EMS
auto" + "Max A") instead of the same Solar/Fixed concept the app uses. The control model also can't
express "Solar mode, but with a remembered Fixed fallback," which the MANUAL auto-revert needs.

## Decisions (from brainstorming)

- **EMS mode** decides device allocation only; **charger mode** controls the charger only; neither
  leaks into the other.
- **Solar surplus requires EMS AUTO.** In MANUAL it auto-reverts to **Fixed** at the stored level.
- **AUTO + Fixed:** charger is set to the fixed level; the EMS balances total consumption via the
  battery on the *measured* grid (per the prerequisite spec).
- **`/ocpp-ui` mirrors the app:** Solar/Fixed control replaces the "EMS auto" checkbox. The global
  EMS AUTO/MANUAL toggle is **not** on the charger page.
- **Charger control is shared + persisted** per charger (survives restart).
- **"Max A" is removed.** The Fixed level is the only user cap; `config.chargingCurrent.max` bounds
  solar-surplus and clamps Fixed for safety.
- **Fixed power is set in amps.**

## Behaviour matrix

| EMS mode | Charger mode | Charger amps | Battery / heat pump |
|---|---|---|---|
| AUTO | Solar | surplus (strategy) | EMS allocates (battery balances measured grid; heat pump throttled) |
| AUTO | Fixed | `fixedAmps` (clamp to config max) | EMS allocates around the measured charger draw |
| MANUAL | Solar → **Fixed** | stored `fixedAmps` | released to own logic |
| MANUAL | Fixed | `fixedAmps` | released |
| any | `charging=false` (Stop) | 0 | per above |
| any | car `NotConnected` | 0 | per above |

## Data model + protocol

Replace the `ChargingState` sealed class (mode+stop conflated, watts) with an explicit record:

```kotlin
@Serializable
data class ChargerControl(
    val mode: ChargerMode,   // SOLAR | FIXED
    val fixedAmps: Int,      // Fixed level; also the MANUAL/auto-revert fallback (always carried)
    val charging: Boolean,   // Start/Stop
)
@Serializable enum class ChargerMode { SOLAR, FIXED }
```

Defined identically in the server's `Messages.kt` and the app's `WsMessage.kt` (matching
`@SerialName`s — same cross-process discipline as the existing messages). Protocol messages:

- Client→server: `SetCharging(control: ChargerControl)` (replaces the old `SetCharging(ChargingState)`).
- Server→client: `ChargerControlUpdate(control: ChargerControl)` (replaces `ChargingStateUpdate`),
  pushed on change and on auth (via the existing `chargingStateFlow` collector pattern).

Carrying `fixedAmps` even in Solar mode makes the MANUAL→Fixed auto-revert deterministic.

## Server control logic

`EnergyManager` holds the current `ChargerControl` (backed by the flow) and resolves the charger
each tick:

```kotlin
fun effectiveChargerAmps(c: ChargerControl, emsAuto: Boolean, connected: Boolean, configMax: Int): Int? {
    if (!connected || !c.charging) return 0
    val mode = if (emsAuto) c.mode else ChargerMode.FIXED   // auto-revert in MANUAL
    return when (mode) {
        ChargerMode.FIXED -> c.fixedAmps.coerceIn(0, configMax)   // forced override
        ChargerMode.SOLAR -> null                                  // strategy computes surplus
    }
}
```

- `null` → solar-surplus path (AUTO only, since MANUAL coerces to FIXED): fed to the strategy as
  `chargerOverrideAmps = null`.
- a number → forced override fed to the strategy (AUTO) or applied directly (MANUAL charger-leg),
  reusing the existing `chargerOverrideAmps` plumbing. `connected` comes from the existing
  `ChargerConnection` (NotConnected → 0).
- The battery/heat-pump allocation runs only in AUTO (unchanged: MANUAL releases them); the charger
  leg is applied in both modes. The battery balances the **measured** grid (prerequisite spec), so a
  Fixed charger the car under-draws is balanced correctly.

`OcppCharger.setMaxChargerPower` drops the `emsAutoControl`/`maxCurrentA` gating (those settings are
gone); it clamps to `config.chargingCurrent.max` and otherwise sends the commanded amps.

## Persistence + wiring

- A new `ChargerControlStore` (Exposed table on the existing SQLite `Database`), keyed by charger
  identity, storing `mode`, `fixedAmps`, `charging`. This **replaces** the current
  `ChargerSettingsStore` (`maxCurrentA`/`emsAutoControl`), which is removed along with
  `OcppService.getChargerSettings`/`putChargerSettings` and the `/ocpp-ui/api/settings/{id}`
  endpoints (those knobs no longer exist). The old table is dropped/recreated — charger settings are
  operational state, safe to reset; device/idTag/transaction data is untouched.
- **`EnergyManager` owns the charger control** and the store (injected via DI): loads the persisted
  control on startup (default `SOLAR`, `fixedAmps = config.chargingCurrent.max`, `charging = true`);
  `setCharging()` updates the flow **and** persists. `OcppService` no longer deals with charger
  settings.
- Both entry points route through `EnergyManager.setCharging` → single source of truth:
  - app via `/ws` `SetCharging`;
  - `/ocpp-ui` via new `GET`/`POST /ocpp-ui/api/chargepoints/{id}/charger-control`. `EnergyManager`
    is passed into `configureOcppWebUi` alongside `OcppService` so these endpoints read/write it.
    (Single configured charger, so the `{id}` maps to that charger; multi-charger is out of scope.)

## App UI (Charger tab)

- Solar/Fixed toggle **gated by EMS mode**: MANUAL disables Solar and shows Fixed (auto-revert) with
  a one-line note; AUTO offers both. Uses the existing `mode` flow.
- Fixed control is an **amps** input/slider over `config` min–max (≈6–32 A) instead of watts.
- Start/Stop and the no-car / connected-idle / charging states (from the prior feature) unchanged.
- Reflects the server's authoritative `ChargerControl` (`ChargerControlUpdate`).

## `/ocpp-ui`

- Replace the per-charger **"EMS auto" + "Max A" + Save** block with the **same Solar/Fixed +
  fixed-amps + Start/Stop** control, reading/writing the persisted `ChargerControl` via the new
  endpoints. Show the current mode/level.
- The global EMS AUTO/MANUAL toggle is **not** added here.
- Keep the low-level **Set current / Clear profile / Reset / Stop** controls as recovery/admin tools.

## Testing

- **Server:** `effectiveChargerAmps` matrix (EMS mode × charger mode × charging × connected);
  `EnergyManager` applies Fixed/Solar/Stop and auto-reverts Solar→Fixed in MANUAL; persistence
  round-trip (save → reload → same control); `setCharging` updates flow + persists; `OcppCharger`
  clamps to config max (no `emsAutoControl` gate).
- **App:** charger-mode toggle gating by EMS mode (pure function over `(emsMode, chargerControl)` →
  UI state); `SetCharging`/`ChargerControlUpdate` round-trip; amps slider bounds.
- **`/ocpp-ui`:** `charger-control` GET/POST endpoint behaviour.

## Migration / compatibility

The protocol change (`ChargingState` → `ChargerControl`) is intentional churn over the just-merged
charger-connection feature — older app/server builds won't interop, which is acceptable (single
operator, app + server deployed together). The SQLite charger-settings schema change recreates that
one table.

## Out of scope

- Multi-charger / multi-connector control (single configured charger assumed, as elsewhere).
- The global EMS-mode UX itself (unchanged).
- The battery measured-grid fix (prerequisite spec).
