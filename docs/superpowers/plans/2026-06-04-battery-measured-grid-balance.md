# Battery Balances Measured Grid — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the EMS battery from assuming the charger consumes its commanded setpoint — balance the battery on the *measured* grid instead, fixing steady-state grid export when the car draws less than commanded.

**Architecture:** In `SurplusPriorityStrategy.decide()`, the battery target is computed from a `projectedGrid` that adds the charger setpoint (`chargerConsumption`) and subtracts the measured charger power. That feed-forward term becomes a constant offset when the car under-draws, parking the grid in export. Replace it with the measured grid (`batteryTarget(batteryPower, gridPower)`), making the battery leg identical to `decideDegraded` and robust to the car taking less (or nothing).

**Tech Stack:** Kotlin, kotlin.test, MockK + kotlinx-coroutines-test. Build: `./gradlew test --tests "..."` / `./gradlew build` from the repo root.

**Spec:** `docs/superpowers/specs/2026-06-04-battery-measured-grid-balance-design.md`

---

## File Structure

- `src/main/kotlin/ems/SurplusPriorityStrategy.kt` — the one-line behavioural change (drop the charger feed-forward; remove the now-unused `chargerConsumption`).
- `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt` — battery-target assertions updated from projected to measured-grid values; one projection-artifact test removed; one under-draw regression test added.
- `src/test/kotlin/ems/EnergyManagerTest.kt` — the single tier-1 test that asserts a battery value updated to the measured-grid result.

The charger-amps leg, heat-pump leg, and `decideDegraded` are unchanged.

---

## Task 1: Battery balances measured grid

**Files:**
- Modify: `src/main/kotlin/ems/SurplusPriorityStrategy.kt`
- Test: `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`
- Test: `src/test/kotlin/ems/EnergyManagerTest.kt`

- [ ] **Step 1: Update the strategy unit tests to the measured-grid expectations (this is the failing test)**

In `src/test/kotlin/ems/SurplusPriorityStrategyTest.kt`:

Replace the `large solar surplus …` test body:

```kotlin
    @Test
    fun `large solar surplus — charger gets max amps, battery absorbs the measured export`() {
        // Exporting 2000W, charger at 2000W. available = 4000W -> 17A (3910W) for the charger.
        // Battery balances the MEASURED grid: target = batteryPower - gridPower = 0 - (-2000) = +2000W.
        val decisions = strategy.decide(snapshot(gridPower = -2000, chargerPower = 2000))
        assertEquals(17, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), decisions.batteryCommand)
        assertTrue(decisions.heatpumpConsumeMode is ConsumeMode.Unrestricted)
    }
```

Replace the `importing from grid …` test body (battery now discharges to cover the measured import):

```kotlin
    @Test
    fun `importing from grid — charger reduces and battery covers the measured import`() {
        // Importing 500W, charger at 3000W. available = 2500W -> 10A (2300W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - 500 = -500W (discharge to cover the import).
        val decisions = strategy.decide(snapshot(gridPower = 500, chargerPower = 3000))
        assertEquals(10, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(-500)), decisions.batteryCommand)
    }
```

Replace the `charger surplus clamped to max amps` test body:

```kotlin
    @Test
    fun `charger surplus clamped to max amps`() {
        // Exporting 10000W, charger at 6000W. available = 16000W -> clamped 32A (7360W) for the charger.
        // Battery balances the MEASURED grid: target = 0 - (-10000) = +10000W.
        val decisions = strategy.decide(snapshot(gridPower = -10000, chargerPower = 6000, chargerMaxAmps = 32))
        assertEquals(32, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(10000)), decisions.batteryCommand)
    }
```

Delete the `tiny residual within the deadband leaves the battery untouched` test entirely — its premise (the projection turning a 3000 W export into a ~10 W projected residual) no longer exists; a 3000 W *measured* export is well outside the deadband and the battery now charges it:

```kotlin
    // (removed: `tiny residual within the deadband leaves the battery untouched`)
```

Replace the `holds current battery power when the grid is balanced within the deadband` test body (same result, comment corrected — no projection):

```kotlin
    @Test
    fun `holds current battery power when the grid is balanced within the deadband`() {
        // Battery already charging 500W; measured grid only 40W off -> within the 50W deadband -> hold 500W.
        // available = 0 + 500 + 40 = 540W -> 2A < 6A min -> charger 0.
        val decisions = strategy.decide(snapshot(gridPower = -40, chargerPower = 0, batteryPower = 500))
        assertEquals(0, decisions.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(500)), decisions.batteryCommand)
    }
```

Replace the `charger override is used verbatim and battery projects from it` test (the battery no longer projects from the override — it balances the measured grid):

```kotlin
    @Test
    fun `charger override is used verbatim; battery still balances the measured grid`() {
        // override 5A; grid -2000 export, charger 0. Charger amps = override = 5.
        // Battery balances the MEASURED grid: target = 0 - (-2000) = +2000W (independent of the override).
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 0, chargerOverrideAmps = 5))
        assertEquals(5, d.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }
```

Add a new regression test right after it (car draws less than commanded → battery soaks the measured surplus, no export, no bogus discharge):

```kotlin
    @Test
    fun `fixed charger the car under-draws — battery soaks the measured surplus, not the setpoint`() {
        // Fixed 16A commanded, but the car only draws 1000W (measured), so the grid is still exporting
        // 2000W. The OLD projection would discharge the battery (projectedGrid = -2000 + 3680 - 1000 =
        // +680 -> battery -680, discharging while exporting). Balancing the MEASURED grid instead:
        // target = 0 - (-2000) = +2000W (charge), soaking the surplus the car won't take.
        val d = strategy.decide(snapshot(gridPower = -2000, chargerPower = 1000, chargerOverrideAmps = 16))
        assertEquals(16, d.chargerMaxAmps)
        assertEquals(BatteryCommand.SetPower(Watt(2000)), d.batteryCommand)
    }
```

(The `surplus below charger minimum`, `zero solar and importing`, `heat pump …`, `deficit discharges …`, `corrects an over-discharge …`, `degraded …`, `charger override zero …`, and `charger override clamped …` tests are unchanged — their charger leg was already 0 or they assert only charger amps / heat pump / `decideDegraded`, so the measured-grid battery value matches what they already assert.)

- [ ] **Step 2: Run the strategy tests to verify they fail**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: FAIL — the updated `large solar surplus`, `importing from grid`, `charger surplus clamped`, `charger override is used verbatim …`, and the new under-draw test fail against the current projection-based implementation.

- [ ] **Step 3: Make the strategy balance the measured grid**

In `src/main/kotlin/ems/SurplusPriorityStrategy.kt`, replace this block inside `decide()`:

```kotlin
        val chargerConsumption = chargerAmps * 230

        // Battery soaks up whatever imbalance the charger didn't.
        // projectedGrid = the grid we'd see once the new charger setpoint lands, before the battery moves.
        val projectedGrid = snapshot.gridPower.value + chargerConsumption - snapshot.chargerPower.value
        val batteryTarget = batteryTarget(snapshot.batteryPower.value, projectedGrid)
```

with:

```kotlin
        // Battery balances the MEASURED grid, not the charger setpoint: the charger's real draw —
        // whatever the car actually takes — already shows up in gridPower. Feeding the commanded
        // setpoint forward instead parked the grid in steady-state export whenever the car drew less
        // than commanded (e.g. car full). The battery's own-power deadbeat (gain 1) is unchanged.
        val batteryTarget = batteryTarget(snapshot.batteryPower.value, snapshot.gridPower.value)
```

(`chargerConsumption` is removed; `chargerAmps` is still returned as `chargerMaxAmps`.)

- [ ] **Step 4: Run the strategy tests to verify they pass**

Run: `./gradlew test --tests "io.konektis.ems.SurplusPriorityStrategyTest"`
Expected: PASS (all, including the new regression test).

- [ ] **Step 5: Update the one affected EnergyManager tier-1 test**

In `src/test/kotlin/ems/EnergyManagerTest.kt`, replace the `tier1 full data runs cascade and sets battery remainder` test:

```kotlin
    @Test fun `tier1 full data runs cascade and balances the measured grid`() = runTest {
        val bat = battery(0)
        // grid -3000 exporting, charger 0, heat pump 0 -> charger gets 13A (2990W).
        // Battery balances the MEASURED grid: target = 0 - (-3000) = +3000W.
        val world = World(grid(-3000), mapOf("c" to charger(0)), emptyMap(),
            mapOf("h" to heatpump(0)), mapOf("b" to bat))
        manager(world).tick()
        coVerify { bat.setChargingPower(Watt(3000)) }
    }
```

- [ ] **Step 6: Run the full server build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all tests pass (the other `EnergyManagerTest` cases assert the charger leg, `decideDegraded`, releases, or a released battery in MANUAL, none of which changed).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/ems/SurplusPriorityStrategy.kt src/test/kotlin/ems/SurplusPriorityStrategyTest.kt src/test/kotlin/ems/EnergyManagerTest.kt
git commit -m "fix(ems): balance battery on measured grid, not charger setpoint

The battery target projected the commanded charger consumption, which became a
constant offset when the car drew less than commanded (e.g. battery full) and
parked the grid in steady-state export. Balance the measured grid instead so
the charger's actual draw is what gets balanced."
```

---

## Self-Review Notes

- **Spec coverage:** the spec's single decision (battery balances measured grid; drop the charger feed-forward) is Step 3; the test updates (Steps 1, 5) and the under-draw regression test cover the spec's Testing section.
- **Recomputed values** (`batteryTarget = batteryPower − gridPower`, 50 W deadband): large surplus +90→+2000; importing +200→−500; clamped +8640→+10000; override-from-it +850→+2000; tier-1 EnergyManager 0→+3000. Unchanged where the charger leg was already 0 (`surplus below min`, `zero solar`, `over-discharge`, deadband-hold) since the removed term `(chargerConsumption − chargerPower)` was 0 there.
- **No placeholders;** every step shows the exact code/command.
