# App Internationalization — Dutch — Design

**Date:** 2026-06-04
**Status:** Approved (brainstorming)
**Scope:** Android app only. Independent of the charger-connection-flow spec
(`2026-06-04-charger-connection-flow-design.md`); can ship before or after it.

## Problem

The app UI is English-only, with every user-facing string hardcoded inline in Compose
(`res/values/strings.xml` contains only `app_name`). It should be available in **Dutch** as well.

## Decision

- **Follow the system locale.** Phone set to Dutch → Dutch UI; otherwise English (the default).
  No in-app language picker.
- English is the default/base resource set; Dutch is an overlay.

## Approach

Standard Android resource localisation:

1. **Extract** every hardcoded user-facing string into `res/values/strings.xml` (English base).
   Replace literals in Compose with `stringResource(R.string.…)` (and `stringResource(id, args)` for
   formatted strings). Screens/components to sweep:
   - `ui/dashboard/DashboardScreen.kt` (tab labels, EMS MODE card, manual-switch dialog, connection
     banner).
   - `ui/overview/OverviewScreen.kt`
   - `ui/charger/ChargerScreen.kt` (mode toggle, Start/Stop, idle/charging text, status text)
   - `ui/heatpump/HeatPumpScreen.kt`
   - `ui/settings/SettingsScreen.kt` (field labels, placeholders, buttons)
   - shared `ui/components/*` (e.g. `StatusHero`, `DeviceCard`) where they hold literals.
   - content descriptions (e.g. the Settings icon) for accessibility.
2. **Translate** into `res/values-nl/strings.xml` with the same keys.
3. **Formatting:** use positional/format-arg strings (`%1$s`, `%1$d`) for interpolated text such as
   `"Disconnected — %1$s"`. Power/number formatting in `PowerFormat` stays numeric; verify locale
   decimal handling is acceptable (kW values).
4. **Naming convention:** group keys by screen with a prefix, e.g. `charger_start`, `charger_stop`,
   `charger_no_car`, `mode_auto`, `mode_manual`, `settings_edge_key_label`. Reuse shared keys
   (`action_cancel`, etc.) where the same word recurs.

No `AppCompat`/per-app-locale wiring is needed since we follow the system locale; the resource
qualifier (`values-nl`) is sufficient.

## Sequencing vs. the charger-flow spec

The charger flow introduces new strings ("No car connected", the MANUAL+ExcessPower note). To avoid
churn:
- If charger-flow lands **first**, it adds its strings as hardcoded literals and this i18n pass
  extracts them with the rest.
- If i18n lands **first**, the charger-flow work adds its new strings directly as
  `R.string.*` entries in both locales.

Either order works; the implementation plan for whichever lands second accounts for the other's
strings.

## Testing

- Build passes with extracted resources (`./gradlew assembleDebug`).
- Manual locale check: set the emulator/device to Dutch and to English; verify every screen renders
  translated text and no literal English leaks (spot-check each screen).
- Optional lint: enable/observe `HardcodedText` / `MissingTranslation` Android lint to catch missed
  strings and untranslated keys.

## Out of scope

- Languages other than English + Dutch.
- An in-app language picker (system locale only).
- Translating dynamic server-provided content (device names from config, server error text).
