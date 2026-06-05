# Charger Minimum Current in Solar Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a solar charging session is active, never allocate the charger below its minimum current — floor at `chargerMinAmps` instead of dropping to 0 — to stop the relay-chattering on/off oscillation near the minimum.

**Architecture:** One change in `SurplusPriorityStrategy.decide()`'s surplus branch (the path taken only when `chargerOverrideAmps == null`, which `EnergyManager` passes only during an active solar session). Clamp the surplus allocation to `[minAmps, maxAmps]` instead of returning 0 below the minimum. The battery leg is unchanged — it balances the measured grid, so the floored charger draw is reconciled like any other load (battery first, then grid).

**Tech Stack:** Kotlin, kotlin.test. Build (repo root): `./gradlew test --tests "..."` / `./gradlew build`.

**Spec:** `docs/superpowers/specs/2026-06-05-charger-minimum-current-design.md`

---

## File Structure

- `src/main/kotlin/ems/SurplusPriorityStrategy.kt` — the one-expression change to the charger leg.
- `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt` — three surplus-path cases now expect the minimum instead of 0; one new regression test.

`EnergyManager`, `decideDegraded`, the heat-pump leg, and the override (Fixed/Stop) path are unchanged.

---

## Task 1: Floor the charger at its minimum in the surplus path

**Files:**
- Modify: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`
- Test: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`

- [ ] **Step 1: Update the affected tests + add the regression (this is the failing test)**

In `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`:

Replace `surplus below charger minimum — charger stops, battery charges the surplus`:
```kotlin
    @Test
    fun `surplus below charger minimum — charger held at minimum during the session`() {
        // Exporting 200W. 200/230 < 6A, but an active solar session floors the charger at its 6A minimum
        // (the difference is imported). Battery balances the measured grid: 0 - (-200) = +200W.
        val decisions = strategy.decide(snapshot(gridPower = -200, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(6, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(200)), decisions.batteryCommand)
    }
```

Replace `zero solar and importing — charger stops, battery covers the deficit`:
```kotlin
    @Test
    fun `zero solar and importing — charger held at minimum, battery covers the measured deficit`() {
        // Importing 1500W, no solar. The active solar session still holds the charger at 6A min.
        // Battery balances the measured grid: 0 - 1500 = -1500W (discharge).
        val decisions = strategy.decide(snapshot(gridPower = 1500, chargerPower = 0))
        assertEquals(6, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(-1500)), decisions.batteryCommand)
    }
```

Replace `holds current battery power when the grid is balanced within the deadband`:
```kotlin
    @Test
    fun `holds current battery power when the grid is balanced within the deadband`() {
        // Battery already charging 500W; measured grid only 40W off -> within the 50W deadband -> hold 500W.
        // Surplus maps to 2A < 6A, but the active solar session floors the charger at 6A min.
        val decisions = strategy.decide(snapshot(gridPower = -40, chargerPower = 0, batteryPower = 500))
        assertEquals(6, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(500)), decisions.batteryCommand)
    }
```

Add a new regression test (place it after `charger override clamped to max amps`):
```kotlin
    @Test
    fun `no surplus during an active solar session still holds the charger at minimum`() {
        // Grid balanced (no surplus, no import). The surplus path (active solar session) still floors
        // the charger at its minimum rather than dropping to 0 — preventing on/off relay chatter.
        val decisions = strategy.decide(snapshot(gridPower = 0, chargerPower = 0, chargerMinAmps = 6))
        assertEquals(6, decisions.chargerMaxAmps)
    }
```

(The override-path tests — `charger override zero stops the charger`, `charger override clamped to max amps`, `charger override is used verbatim …` — are unchanged: the override clamps to `[0, maxAmps]`, so a stopped charger is still 0. The surplus tests that already map to ≥6A — `large solar surplus`, `importing from grid`, `charger surplus clamped`, the under-draw regression — are unchanged.)

- [ ] **Step 2: Run the tests to verify the updated ones fail**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: FAIL — the three updated cases (and the new one) expect `6` but the current code returns `0` below the minimum.

- [ ] **Step 3: Floor the charger at its minimum in the strategy**

In `src/main/kotlin/ems/SurplusPriorityStrategy.kt`, replace the `chargerAmps` computation in `decide(...)`:
```kotlin
        // Car charger: forced override (Stop/Fixed) wins; otherwise assign the available surplus.
        // During an active solar session (override == null) never drop below the minimum — a car won't
        // charge below it, so dropping to 0 just chatters the relays. The shortfall is imported (the
        // battery, balancing the measured grid, covers it first).
        val chargerAmps = snapshot.chargerOverrideAmps?.coerceIn(0, snapshot.chargerMaxAmps)
            ?: (available / 230).coerceIn(snapshot.chargerMinAmps, snapshot.chargerMaxAmps)
```

(This removes the old `when { available <= 0 -> 0; else -> { … amps < min -> 0 … } }` block; `coerceIn` floors any below-minimum or non-positive value up to `chargerMinAmps`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: PASS (all, including the updated and new cases).

- [ ] **Step 5: Full server build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — `EnergyManagerTest` and the rest stay green (their tier-1 cases use `charger(0)` with surplus well above the minimum, so the floor doesn't change them).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ems/SurplusPriorityStrategy.kt src/test/kotlin/ems/SurplusPriorityStrategyTest.kt
git commit -m "feat(ems): hold the charger at its minimum current during an active solar session

A car won't charge below its minimum, so dropping the surplus allocation to 0
just chatters the charger/car relays. Floor at chargerMinAmps instead; the
shortfall is imported (battery covers it first via the measured-grid balance)."
```

---

## Self-Review Notes

- **Spec coverage:** the floor-at-minimum decision → Step 3's `coerceIn(minAmps, maxAmps)`; "only during an active solar session" is structural (the surplus branch runs only when `EnergyManager` passes `chargerOverrideAmps == null`, i.e. connected + charging + AUTO + SOLAR); the battery-covers-shortfall consequence is the unchanged measured-grid battery leg; test deltas match the spec's listed cases (the three 0→6 updates + a new regression).
- **Arithmetic check:** `gridPower=-200` → available 200 → `200/230=0` → `coerceIn(6,32)=6`; `gridPower=1500` → available −1500 → `−6` → `6`; `gridPower=-40,battery=500` → available 540 → `2` → `6`; `gridPower=0` → 0 → `6`. Battery values unchanged (`batteryPower − gridPower`).
- **No placeholders:** full code/commands in every step.
