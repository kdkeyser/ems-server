# Charger + Car Scene (Charger Tab) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Charger tab's single icon tile with a charger→car scene — a ghost car when no car is connected, a solid car when plugged in, and a pulsing lightning bolt while charging.

**Architecture:** A pure `chargerSceneSpec(uiState)` maps the existing `ChargerUiState` to `(showCar, carDimmed, charging)`. A `ChargerScene` composable renders the stock `EvStation` charger + `DirectionsCar` car + a pulsing `Bolt`, and a `ChargerHero` composable wraps it with the existing value/status text in a Card. `ChargerScreen` swaps both its `StatusHero` calls for `ChargerHero`; the shared `StatusHero` (used by the heat-pump tab) is untouched.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3, `material-icons-extended`, `animation-core`), JUnit. Build (from `android/`): `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` / `:app:assembleDebug`.

**Spec:** `docs/superpowers/specs/2026-06-05-charger-scene-ui-design.md`

---

## File Structure

- `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt` — **new**: `ChargerSceneSpec` + pure `chargerSceneSpec(uiState)`, the `ChargerScene` composable (icons + pulse), and the `ChargerHero` composable (Card with scene + value + status).
- `android/app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt` — add `Car` and `Charging` icons.
- `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt` — replace the two `StatusHero` calls with `ChargerHero`.
- `android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt` — **new**: unit test for `chargerSceneSpec`.

`ChargerUiState`/`chargerUiState`/`parseChargerConnection` (in `ChargerUiState.kt`) and `ChargerControls` (private in `ChargerScreen.kt`) are unchanged.

---

## Task 1: `chargerSceneSpec` pure mapping

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.ui.charger.ChargerSceneSpec
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerSceneSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerSceneTest {
    @Test fun `maps ui state to scene spec`() {
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = true, charging = false),
            chargerSceneSpec(ChargerUiState.NO_CAR))
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = false, charging = false),
            chargerSceneSpec(ChargerUiState.CONNECTED_IDLE))
        assertEquals(ChargerSceneSpec(showCar = true, carDimmed = false, charging = true),
            chargerSceneSpec(ChargerUiState.CHARGING))
        assertEquals(ChargerSceneSpec(showCar = false, carDimmed = false, charging = false),
            chargerSceneSpec(ChargerUiState.CONTROLS_FALLBACK))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerSceneTest"`
Expected: FAIL — `ChargerSceneSpec`/`chargerSceneSpec` unresolved.

- [ ] **Step 3: Create the spec type + pure function**

Create `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt` with (composables added in Task 2):

```kotlin
package io.konektis.ems.ui.charger

/** What the charger scene draws for a given UI state. */
data class ChargerSceneSpec(
    val showCar: Boolean,
    val carDimmed: Boolean,
    val charging: Boolean,
)

fun chargerSceneSpec(uiState: ChargerUiState): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR -> ChargerSceneSpec(showCar = true, carDimmed = true, charging = false)
    ChargerUiState.CONNECTED_IDLE -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = false)
    ChargerUiState.CHARGING -> ChargerSceneSpec(showCar = true, carDimmed = false, charging = true)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, charging = false)
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerSceneTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt
git commit -m "feat(app): chargerSceneSpec mapping for the charger scene"
```

---

## Task 2: `ChargerScene` + `ChargerHero` composables + icons

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt`

No new unit test (Compose visuals are verified by compile + manual). TDD doesn't apply to the rendering; the pure logic is covered by Task 1.

- [ ] **Step 1: Add the car + charging icons to `EmsIcons`**

In `android/app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt`, add the import `import androidx.compose.material.icons.filled.DirectionsCar` (next to the other icon imports; `Bolt` is already imported), and inside `object EmsIcons` add:

```kotlin
    val Car: ImageVector = Icons.Filled.DirectionsCar
    val Charging: ImageVector = Icons.Filled.Bolt
```

- [ ] **Step 2: Add the composables to `ChargerScene.kt`**

Append to `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt` (keep the existing `ChargerSceneSpec`/`chargerSceneSpec` from Task 1; add the imports at the top of the file):

```kotlin
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

private val ChargingAmber = Color(0xFFFBBF24)
private val CableGrey = Color(0xFF334155)

/** Charger → (cable + bolt) → car. Bolt pulses while charging; car dims to a ghost when absent. */
@Composable
fun ChargerScene(spec: ChargerSceneSpec, modifier: Modifier = Modifier) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(EmsIcons.Charger, contentDescription = "Charger", tint = iconTint, modifier = Modifier.size(46.dp))
        if (spec.showCar) {
            Cable(live = spec.charging)
            if (spec.charging) {
                PulsingBolt()
                Cable(live = true)
            }
            Icon(
                EmsIcons.Car,
                contentDescription = "Car",
                tint = iconTint,
                modifier = Modifier.size(46.dp).alpha(if (spec.carDimmed) 0.16f else 1f),
            )
        }
    }
}

@Composable
private fun Cable(live: Boolean) {
    Box(
        Modifier
            .width(16.dp)
            .height(3.dp)
            .background(if (live) ChargingAmber else CableGrey, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun PulsingBolt() {
    val t = rememberInfiniteTransition(label = "bolt")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha",
    )
    val s by t.animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "scale",
    )
    Icon(
        EmsIcons.Charging,
        contentDescription = "Charging",
        tint = ChargingAmber,
        modifier = Modifier.size(24.dp).graphicsLayer { alpha = a; scaleX = s; scaleY = s },
    )
}

/** Charger-tab hero: the scene, the big value, and the status line with a colored dot. */
@Composable
fun ChargerHero(uiState: ChargerUiState, chargerW: Int?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val isCharging = uiState == ChargerUiState.CHARGING
    val online = uiState != ChargerUiState.NO_CAR &&
        (uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null)
    val value = when {
        uiState == ChargerUiState.NO_CAR -> "No car"
        isCharging && chargerW != null -> formatWatts(chargerW)
        isCharging -> "Charging"
        else -> "Idle"
    }
    val statusText = when (uiState) {
        ChargerUiState.NO_CAR -> "No car connected"
        ChargerUiState.CHARGING -> "Charging"
        ChargerUiState.CONNECTED_IDLE -> "Connected — not charging"
        ChargerUiState.CONTROLS_FALLBACK -> if (chargerW != null) "Charger online" else "Status unavailable"
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChargerScene(chargerSceneSpec(uiState))
            Text(value, color = if (isCharging) ems.consumption else ems.idle, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
```

Add the missing `Box` import too: `import androidx.compose.foundation.layout.Box`.

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt
git commit -m "feat(app): ChargerScene + ChargerHero composables (charger/car/pulsing bolt)"
```

---

## Task 3: Wire `ChargerHero` into `ChargerScreen`

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`

- [ ] **Step 1: Replace the two `StatusHero` calls with `ChargerHero` + a single controls section**

In `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`, replace the entire `when (uiState) { ... }` block (the `NO_CAR ->` branch and the `else ->` branch, i.e. both `StatusHero` calls and the controls/credentials block) with:

```kotlin
        ChargerHero(uiState = uiState, chargerW = chargerW)

        if (uiState != ChargerUiState.NO_CAR) {
            if (isAuthenticated && chargerControl != null) {
                ChargerControls(
                    isCharging = isCharging,
                    control = chargerControl,
                    mode = mode,
                    onSetCharging = onSetCharging,
                )
            } else {
                Text(
                    "Charger control unavailable — check credentials in Settings.",
                    fontSize = 14.sp,
                    color = ems.idle,
                )
            }
        }
```

Then remove imports that are now unused in `ChargerScreen.kt`: `StatusHero`, `EmsIcons`, and `formatWatts` (these now live in `ChargerScene.kt`). Keep `LocalEmsColors` (still used for `ems.idle` in the credentials text) and `ControlState`. Leave the `ChargerControls` private composable and the rest of the file unchanged. If the Kotlin compiler reports any remaining unused import (e.g. `ChargerUiState` is still used), keep what's referenced and drop only what isn't.

- [ ] **Step 2: Build + tests + APK**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (incl. `ChargerSceneTest`, `ChargerUiStateTest`).

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt
git commit -m "feat(app): Charger tab uses the charger+car scene hero"
```

---

## Self-Review Notes

- **Spec coverage:** scene spec + pure fn → Task 1; `ChargerScene` (EvStation + DirectionsCar + pulsing Bolt, ghost car, amber cable) + `ChargerHero` + icons → Task 2; `ChargerScreen` rewiring (NO_CAR and else both use the hero; controls unchanged) → Task 3; `CONTROLS_FALLBACK` → charger-only via `showCar = false`; `StatusHero` left untouched (heat-pump tab).
- **Type consistency:** `ChargerSceneSpec(showCar, carDimmed, charging)` and `chargerSceneSpec(uiState)` identical across Tasks 1–2 and the test; `ChargerHero(uiState, chargerW)` matches the Task 3 call site; `EmsIcons.Car`/`EmsIcons.Charging`/`EmsIcons.Charger` referenced consistently.
- **No placeholders;** every code step is complete. Animation/Compose rendering isn't unit-tested (manual on device); the only unit test is the pure `chargerSceneSpec` mapping, consistent with the existing `ChargerUiStateTest`.
