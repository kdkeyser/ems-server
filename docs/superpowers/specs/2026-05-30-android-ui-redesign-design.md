# Android EMS — UI Redesign Design

**Date:** 2026-05-30
**Status:** Approved (design), pending implementation plan
**Scope:** Visual/UX redesign of the existing Android app (`android/`). No backend or
WebSocket-protocol changes.

## Goal

The current app works end-to-end but looks rough: it uses the bare default Material 3
theme (no color scheme, typography, or shapes defined), emoji icons (☀️🔌🏠🔋), and a
hand-rolled Canvas topology view. Redesign it into a slick, cohesive, Material 3-compliant
interface with clean vector icons and a polished energy-flow visualization, across all four
screens (Overview, Charger, Heat Pump, Settings).

This is a presentation-layer redesign. Data models, ViewModels' public behavior, WebSocket
clients, DI, and settings persistence stay as-is except where a screen needs a value that is
already available in `StatusState` / `ControlState`.

## Design Principles

- **Material 3 throughout** — proper `ColorScheme`, typography, shapes, and M3 components
  (`Card`, `Slider`, `SegmentedButton`, `NavigationBar`, `TopAppBar`, `FilledTonalButton`).
- **No emoji** — all iconography uses Material vector icons from `material-icons-extended`
  (already a dependency). Emoji are the single biggest "amateur" tell in the current UI.
- **Values live with their icons, never on connecting lines** — the SMA Energy app pattern.
  This is the core fix for the energy-flow diagram's long-standing label-overlap problem.
- **Honest data** — screens show only what the protocol actually provides. No fabricated
  fields.

## 1. Theme Foundation

New package `ui/theme/`:

- **`Color.kt`** — a fixed, green-seeded Material 3 `ColorScheme` with full **light and dark**
  role sets (background, surface, surfaceVariant, primary, onPrimary, etc.). We deliberately
  do **not** use Android 12 dynamic color, so the brand/energy palette stays consistent and
  is not overridden by the device wallpaper.
- **Semantic energy colors** — M3 roles don't express "producing vs consuming". Add a small
  `EmsColors` holder (production-green, consumption-red, idle/neutral, plus per-device accent
  tints and tonal tile backgrounds) with light and dark variants, exposed via a
  `CompositionLocal` (e.g. `LocalEmsColors`). This replaces the ad-hoc top-level
  `colorGreen` / `colorRed` / `colorGray` constants currently in `DeviceNode.kt` and the
  hardcoded `Color(0x...)` literals scattered across screens.
- **`Type.kt`** — Material 3 type scale (can stay close to default initially; the point is to
  define it centrally).
- **`Shape.kt`** — rounded shapes: ~16–20dp for tiles/cards, fully-rounded for pills.
- **`EmsTheme { content }`** — top-level theme composable. Selects light/dark via
  `isSystemInDarkTheme()`, installs the `ColorScheme`, typography, shapes, and provides
  `LocalEmsColors`. `MainActivity` switches from the bare `MaterialTheme {}` to `EmsTheme {}`.

### Icon mapping (material-icons-extended)

| Node / device | Icon |
|---|---|
| Solar | `Icons.Filled.SolarPower` (fallback `WbSunny`) |
| Grid | `Icons.Filled.Bolt` (fallback `ElectricalServices`) |
| House | `Icons.Filled.Home` |
| Battery | `Icons.Filled.BatteryChargingFull` |
| Car charger | `Icons.Filled.EvStation` |
| Heat pump | `Icons.Filled.HeatPump` (fallback `Thermostat`) |
| Settings | `Icons.Filled.Settings` |

Exact icon names verified against the library at implementation time; fallbacks listed where
the primary may not exist in the bundled version.

## 2. Overview Screen (centerpiece)

Layout top-to-bottom inside a scrollable column:

1. **Top app bar** — title ("Energy") + settings action icon.
2. **Connection banner** — only rendered for `Connecting` / `Disconnected` (already
   implemented; keep).
3. **Energy-flow hero card** — a rounded surface containing the flow diagram:
   - Four nodes: **Solar** (top center), **House** (center, visual anchor with primary tint),
     **Grid** (bottom left), **Battery** (bottom right).
   - Each node is a rounded tonal icon tile with its **power value directly beneath the icon**
     and a small caption (e.g. "Grid · import", "Battery · 62%"). **No text on the lines.**
   - **Connecting lines**: short, thin (~1.5–2dp), **solid** segments that sit only in the
     open gap between nodes — endpoints inset well clear of both icons so a line never passes
     behind an icon. Each line has a small filled **arrowhead** indicating flow direction.
   - **Color**: green = production / favorable direction (solar→house, battery discharge,
     export to grid); red = consumption / unfavorable (grid import, battery charge). This
     direction+color logic already exists in the current `TopologyView` and is preserved.
   - **Battery node**: a circular **SoC ring** around the battery icon, plus its
     charge/discharge power value below.
   - Lines for zero/absent power are not drawn (matches current behavior).
   - **Animation (optional / lightweight):** a subtle pulse or glow on active lines to convey
     motion. Explicitly **not** dashed/marching-ants. May be deferred; static is acceptable.
4. **"Devices" section** — uppercase section label, then one M3 `Card` per device from
   `StatusState.devices`: leading rounded icon tile, name, status line (online/offline +
   reason or extra-info), trailing color-coded power value. **Offline devices are dimmed.**

`OverviewScreen` keeps its current input (`StatusState?`), `null`/empty → "Waiting for data…".

The current `TopologyView.kt` is **replaced** by a new `EnergyFlowDiagram` component
implementing the above. The current `DeviceNode.kt` composable is already reduced to helpers;
the device-card rendering moves into the redesigned overview/component layer.

## 3. Charger Screen

- **Status hero card** — charger icon tile, large live power value, status line
  ("Charging · Webasto" / "Not charging" / "Unknown").
- **Mode selector** — Material 3 **segmented button** ("Solar surplus" / "Fixed power"),
  replacing the current radio-button rows.
- **Max-power slider** — shown only in Fixed mode; range 1.44–7.68 kW (unchanged), with the
  selected value displayed above and min/max captions below.
- **Action button** — one large button: green **START CHARGING** when idle, red
  **STOP CHARGING** when charging.
- Controls are shown only when `controlState is ControlState.Authenticated`; otherwise a
  message directs the user to Settings (unchanged logic). Mapping to `ChargingState`
  (`NotCharging` / `ChargingWithExcessPower` / `ChargingWithMaxPower`) is unchanged.

## 4. Heat Pump Screen

Kept as a tab (to be extended later when heat-pump control is added to the protocol).
Currently **display-only**, showing only protocol-provided data:

- **Status hero card** — heat pump icon tile, power value (or `—` when offline), online/offline
  state with reason, device name / optional extra-info line.
- A short note: "No controls available — the server exposes heat-pump status only."
- No timestamp, no SG-Ready, no other fabricated metrics.

## 5. Settings Screen

- Top app bar with back navigation (unchanged).
- The three fields (server address, username, password) grouped inside a single M3 `Card`
  with floating labels; password masked.
- Filled **Save** button.
- Defaults and persistence unchanged (`SettingsRepository`).

## 6. Navigation

Unchanged structure: `Scaffold` with a `TopAppBar` (settings action) and a bottom
`NavigationBar` of three tabs — **Home** (Overview), **Charger**, **Heat pump** — using the
mapped Material icons and the M3 pill-highlighted active indicator. Settings remains a separate
destination reached from the top bar.

## Components Inventory

New / rewritten composables (under `ui/components/` and `ui/theme/`):

- `EmsTheme`, `Color.kt`, `Type.kt`, `Shape.kt`, `EmsColors` + `LocalEmsColors`
- `EnergyFlowDiagram` (replaces `TopologyView`)
- `DeviceCard` (M3 card with icon tile, name, status, trailing power)
- `StatusHero` (shared by Charger/Heat Pump: icon tile + big value + status line)
- `IconTile` (rounded tonal background + centered vector icon)
- Power/value formatting helpers (reuse/relocate existing `fmtWatts`, `powerColor`)

## Testing

- Existing unit tests (models, ViewModels, settings) must continue to pass; they are
  presentation-agnostic and should be unaffected.
- This redesign is visual; no new business logic is introduced. Any new pure helpers
  (e.g. SoC-ring sweep fraction, line-endpoint inset math, value formatting) get small unit
  tests where they have non-trivial logic.
- Manual verification on emulator (light and dark) for layout and the flow diagram.

## Out of Scope

- Backend / WebSocket protocol changes (including heat-pump control).
- Charts / history / analytics screens.
- Localization, theming customization UI, dynamic color.
