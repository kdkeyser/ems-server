# App: Reconnect-on-Resume + Charging-Session UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconnect the WebSockets immediately when the app returns to the foreground, and make the charger Start/Stop button reflect the charging *session* (with a pending state and a "session active, car not drawing" hero state) rather than instantaneous power.

**Architecture:** Each WS client gets a `reconnectNow()` backed by a `MutableSharedFlow`; the connect loop is driven by `combine(settingsFlow, reconnectSignal)` so a signal cancels the backoff and reconnects (`MainActivity.onStart()` fires it). The charger button is driven by `chargerControl.charging` (the echoed session) via a pure `chargerButtonState(sessionActive, pending)`; the scene's bolt has three modes (off/armed/charging) via `chargerSceneSpec(uiState, sessionActive)`.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx-coroutines Flow, JUnit. Build (from `android/`): `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` / `:app:assembleDebug`.

**Spec:** `docs/superpowers/specs/2026-06-05-app-reconnect-and-charging-session-ux-design.md`

---

## File Structure

- `data/ws/StatusWsClient.kt`, `data/ws/ControlWsClient.kt` — add `reconnectNow()` + combine the reconnect loop with a reconnect signal.
- `ui/MainActivity.kt` — `onStart()` fires `reconnectNow()` on both clients.
- `res/values/strings.xml`, `res/values-nl/strings.xml` — 4 new strings.
- `ui/charger/ChargerScene.kt` — `BoltMode`, `chargerSceneSpec(uiState, sessionActive)`, `ChargerScene` (3 bolt modes), `ChargerHero(…, sessionActive)`, and the pure `chargerButtonState`.
- `ui/charger/ChargerScreen.kt` — pass `sessionActive` to `ChargerHero`; `ChargerControls` drives the button off `control.charging` with a `pending` state.
- Tests: `ChargerSceneTest.kt` (updated), `ChargerButtonTest.kt` (new).

---

## Task 1: Reconnect immediately on resume

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt`

No unit test (live socket + lifecycle); verified by compile + manual background/resume.

- [ ] **Step 1: `StatusWsClient` — add the reconnect signal + `reconnectNow()`**

In `StatusWsClient.kt`, add imports:
```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
```
Add the field + method to the class (after `_connectionState`):
```kotlin
    private val reconnectSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Force an immediate reconnect (cancels any in-flight backoff). Called on app foreground. */
    fun reconnectNow() { reconnectSignal.tryEmit(Unit) }
```
Change the `statusFlow` source to combine settings with the signal (keep the existing `transformLatest { s -> … }` body unchanged):
```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<StatusState> =
        combine(settings.settingsFlow, reconnectSignal.onStart { emit(Unit) }) { s, _ -> s }
            .transformLatest { s ->
                // … existing body unchanged …
            }
```
(`transformLatest` cancels and restarts its block on each upstream emission, so a `reconnectNow()` abandons a `delay(WS_BACKOFF…)` and reconnects with `attempt = 0`. The `onStart { emit(Unit) }` makes `combine` emit on first collection even before any signal.)

- [ ] **Step 2: `ControlWsClient` — same**

In `ControlWsClient.kt`, add imports:
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
```
Add the field + method (after `_connectionState`):
```kotlin
    private val reconnectSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Force an immediate reconnect (cancels any in-flight backoff). Called on app foreground. */
    fun reconnectNow() { reconnectSignal.tryEmit(Unit) }
```
Change the `init` collector to combine settings with the signal (keep the inner `{ s -> … }` body unchanged):
```kotlin
    init {
        scope.launch {
            combine(settings.settingsFlow, reconnectSignal.onStart { emit(Unit) }) { s, _ -> s }
                .collectLatest { s ->
                    // … existing body unchanged …
                }
        }
    }
```

- [ ] **Step 3: `MainActivity.onStart()` fires both**

In `MainActivity.kt`, add the override (inside the class, after `onCreate`):
```kotlin
    override fun onStart() {
        super.onStart()
        val app = application as EmsApplication
        app.component.statusWsClient.reconnectNow()
        app.component.controlWsClient.reconnectNow()
    }
```

- [ ] **Step 4: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt android/app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt
git commit -m "feat(app): reconnect WebSockets immediately on app resume"
```

---

## Task 2: Charging session button, pending state, and scene bolt modes

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-nl/strings.xml`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/ChargerButtonTest.kt` (create)

- [ ] **Step 1: Add the 4 strings (both locales)**

In `android/app/src/main/res/values/strings.xml`, add inside `<resources>` (near the other `charger_*`):
```xml
    <string name="charger_starting">Starting…</string>
    <string name="charger_stopping">Stopping…</string>
    <string name="charger_value_ready">Ready</string>
    <string name="charger_status_session_not_drawing">Session active — car not drawing</string>
```
In `android/app/src/main/res/values-nl/strings.xml`, add:
```xml
    <string name="charger_starting">Starten…</string>
    <string name="charger_stopping">Stoppen…</string>
    <string name="charger_value_ready">Gereed</string>
    <string name="charger_status_session_not_drawing">Sessie actief — auto laadt niet</string>
```

- [ ] **Step 2: Write the failing pure-function tests**

Replace `android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt` with:
```kotlin
package io.konektis.ems

import io.konektis.ems.ui.charger.BoltMode
import io.konektis.ems.ui.charger.ChargerSceneSpec
import io.konektis.ems.ui.charger.ChargerUiState
import io.konektis.ems.ui.charger.chargerSceneSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ChargerSceneTest {
    @Test fun `maps ui state and session to scene spec`() {
        assertEquals(ChargerSceneSpec(true, true, BoltMode.OFF), chargerSceneSpec(ChargerUiState.NO_CAR, sessionActive = false))
        assertEquals(ChargerSceneSpec(true, true, BoltMode.OFF), chargerSceneSpec(ChargerUiState.NO_CAR, sessionActive = true))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.OFF), chargerSceneSpec(ChargerUiState.CONNECTED_IDLE, sessionActive = false))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.ARMED), chargerSceneSpec(ChargerUiState.CONNECTED_IDLE, sessionActive = true))
        assertEquals(ChargerSceneSpec(true, false, BoltMode.CHARGING), chargerSceneSpec(ChargerUiState.CHARGING, sessionActive = true))
        assertEquals(ChargerSceneSpec(false, false, BoltMode.OFF), chargerSceneSpec(ChargerUiState.CONTROLS_FALLBACK, sessionActive = true))
    }
}
```
Create `android/app/src/test/kotlin/io/konektis/ems/ChargerButtonTest.kt`:
```kotlin
package io.konektis.ems

import io.konektis.ems.ui.charger.ChargerButtonLabel
import io.konektis.ems.ui.charger.chargerButtonState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChargerButtonTest {
    @Test fun `settled button reflects the session`() {
        val start = chargerButtonState(sessionActive = false, pending = null)
        assertEquals(ChargerButtonLabel.START, start.label); assertTrue(start.enabled); assertFalse(start.stopStyle)
        val stop = chargerButtonState(sessionActive = true, pending = null)
        assertEquals(ChargerButtonLabel.STOP, stop.label); assertTrue(stop.enabled); assertTrue(stop.stopStyle)
    }

    @Test fun `pending disables the button and shows progress label`() {
        val starting = chargerButtonState(sessionActive = false, pending = true)
        assertEquals(ChargerButtonLabel.STARTING, starting.label); assertFalse(starting.enabled)
        val stopping = chargerButtonState(sessionActive = true, pending = false)
        assertEquals(ChargerButtonLabel.STOPPING, stopping.label); assertFalse(stopping.enabled)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerSceneTest" --tests "io.konektis.ems.ChargerButtonTest"`
Expected: FAIL — `BoltMode`, the new `chargerSceneSpec` signature, `ChargerButtonLabel`, and `chargerButtonState` are unresolved.

- [ ] **Step 4: Rewrite `ChargerScene.kt`**

Replace the whole file with:
```kotlin
package io.konektis.ems.ui.charger

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.R
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

/** Bolt rendering: OFF = none, ARMED = dim static (session on, no power), CHARGING = pulsing (power flowing). */
enum class BoltMode { OFF, ARMED, CHARGING }

/** What the charger scene draws for a given UI + session state. */
data class ChargerSceneSpec(
    val showCar: Boolean,
    val carDimmed: Boolean,
    val bolt: BoltMode,
)

fun chargerSceneSpec(uiState: ChargerUiState, sessionActive: Boolean): ChargerSceneSpec = when (uiState) {
    ChargerUiState.NO_CAR -> ChargerSceneSpec(showCar = true, carDimmed = true, bolt = BoltMode.OFF)
    ChargerUiState.CONNECTED_IDLE -> ChargerSceneSpec(showCar = true, carDimmed = false, bolt = if (sessionActive) BoltMode.ARMED else BoltMode.OFF)
    ChargerUiState.CHARGING -> ChargerSceneSpec(showCar = true, carDimmed = false, bolt = BoltMode.CHARGING)
    ChargerUiState.CONTROLS_FALLBACK -> ChargerSceneSpec(showCar = false, carDimmed = false, bolt = BoltMode.OFF)
}

/** Start/Stop button appearance, derived from the session intent and any in-flight press. */
enum class ChargerButtonLabel { START, STOP, STARTING, STOPPING }
data class ChargerButtonState(val label: ChargerButtonLabel, val enabled: Boolean, val stopStyle: Boolean)

/** pending: true = starting, false = stopping, null = settled. */
fun chargerButtonState(sessionActive: Boolean, pending: Boolean?): ChargerButtonState = when (pending) {
    true -> ChargerButtonState(ChargerButtonLabel.STARTING, enabled = false, stopStyle = false)
    false -> ChargerButtonState(ChargerButtonLabel.STOPPING, enabled = false, stopStyle = true)
    null -> if (sessionActive) ChargerButtonState(ChargerButtonLabel.STOP, enabled = true, stopStyle = true)
            else ChargerButtonState(ChargerButtonLabel.START, enabled = true, stopStyle = false)
}

private val ChargingAmber = Color(0xFFFBBF24)
private val CableGrey = Color(0xFF334155)

/** Charger → (cable + bolt) → car. Pulsing bolt = power flowing; dim static bolt = session armed. */
@Composable
fun ChargerScene(spec: ChargerSceneSpec, modifier: Modifier = Modifier) {
    val iconTint = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(EmsIcons.Charger, contentDescription = stringResource(R.string.cd_charger), tint = iconTint, modifier = Modifier.size(46.dp))
        if (spec.showCar) {
            Cable(live = spec.bolt == BoltMode.CHARGING)
            if (spec.bolt != BoltMode.OFF) {
                Bolt(spec.bolt)
                Cable(live = spec.bolt == BoltMode.CHARGING)
            }
            Icon(
                EmsIcons.Car,
                contentDescription = stringResource(R.string.cd_car),
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
private fun Bolt(mode: BoltMode) {
    if (mode == BoltMode.CHARGING) {
        val t = rememberInfiniteTransition(label = "bolt")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
        val s by t.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "scale")
        Icon(
            EmsIcons.Charging,
            contentDescription = stringResource(R.string.cd_charging),
            tint = ChargingAmber,
            modifier = Modifier.size(24.dp).graphicsLayer { alpha = a; scaleX = s; scaleY = s },
        )
    } else { // ARMED — session on, no power flowing
        Icon(
            EmsIcons.Charging,
            contentDescription = stringResource(R.string.cd_charging),
            tint = ChargingAmber,
            modifier = Modifier.size(24.dp).alpha(0.4f),
        )
    }
}

/** Charger-tab hero: the scene, the big value, and the status line with a colored dot. */
@Composable
fun ChargerHero(uiState: ChargerUiState, chargerW: Int?, sessionActive: Boolean, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val isCharging = uiState == ChargerUiState.CHARGING
    val online = uiState != ChargerUiState.NO_CAR &&
        (uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null)
    val value = when {
        uiState == ChargerUiState.NO_CAR -> stringResource(R.string.charger_value_no_car)
        isCharging && chargerW != null -> formatWatts(chargerW)
        isCharging -> stringResource(R.string.charger_charging)
        uiState == ChargerUiState.CONNECTED_IDLE && sessionActive -> stringResource(R.string.charger_value_ready)
        uiState == ChargerUiState.CONTROLS_FALLBACK && chargerW != null -> formatWatts(chargerW)
        else -> stringResource(R.string.charger_value_idle)
    }
    val statusText = when {
        uiState == ChargerUiState.NO_CAR -> stringResource(R.string.charger_status_no_car)
        isCharging -> stringResource(R.string.charger_charging)
        uiState == ChargerUiState.CONNECTED_IDLE && sessionActive -> stringResource(R.string.charger_status_session_not_drawing)
        uiState == ChargerUiState.CONTROLS_FALLBACK -> if (chargerW != null) stringResource(R.string.charger_status_online) else stringResource(R.string.charger_status_unavailable)
        else -> stringResource(R.string.charger_status_connected_idle)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChargerScene(chargerSceneSpec(uiState, sessionActive))
            Text(value, color = if (isCharging) ems.consumption else ems.idle, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
```

- [ ] **Step 5: Rewrite the `ChargerScreen` body + `ChargerControls`**

In `ChargerScreen.kt`:

Add imports:
```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
```
Replace the `ChargerScreen` `Column { … }` body (the `ChargerHero(...)` call + the `if (uiState != NO_CAR) { … }` block, and remove the now-unused `val isCharging`):
```kotlin
        ChargerHero(
            uiState = uiState,
            chargerW = chargerW,
            sessionActive = chargerControl?.charging ?: false,
        )

        if (uiState != ChargerUiState.NO_CAR) {
            if (isAuthenticated && chargerControl != null) {
                ChargerControls(
                    control = chargerControl,
                    mode = mode,
                    onSetCharging = onSetCharging,
                )
            } else {
                Text(
                    stringResource(R.string.charger_control_unavailable),
                    fontSize = 14.sp,
                    color = ems.idle,
                )
            }
        }
```
(Delete the line `val isCharging = uiState == ChargerUiState.CHARGING` — it's no longer used in `ChargerScreen`.)

Replace the whole `ChargerControls` composable with:
```kotlin
@Composable
private fun ChargerControls(
    control: ChargerControl,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
) {
    val ems = LocalEmsColors.current
    val sessionActive = control.charging
    // EMS MANUAL makes solar surplus meaningless -> force Fixed.
    val solarAllowed = mode != ManagerMode.MANUAL
    var selectedMode by remember(control, mode) {
        mutableStateOf(if (control.mode == ChargerMode.SOLAR && solarAllowed) ChargerMode.SOLAR else ChargerMode.FIXED)
    }
    var fixedAmps by remember(control) { mutableIntStateOf(control.fixedAmps.coerceIn(6, 32)) }

    // pending: true = starting, false = stopping, null = settled.
    var pending by remember { mutableStateOf<Boolean?>(null) }
    // Clear pending once the server-echoed session reaches the pressed target.
    LaunchedEffect(sessionActive, pending) {
        if (pending != null && pending == sessionActive) pending = null
    }
    // Safety timeout so the spinner can't hang if the session never flips.
    LaunchedEffect(pending) {
        if (pending != null) { delay(30_000); pending = null }
    }

    Text(stringResource(R.string.charger_mode_label), style = MaterialTheme.typography.labelSmall, color = ems.idle)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedMode == ChargerMode.SOLAR,
            enabled = solarAllowed,
            onClick = { selectedMode = ChargerMode.SOLAR },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.charger_mode_solar)) }
        SegmentedButton(
            selected = selectedMode == ChargerMode.FIXED,
            onClick = { selectedMode = ChargerMode.FIXED },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.charger_mode_fixed)) }
    }

    if (!solarAllowed) {
        Text(stringResource(R.string.charger_manual_note), fontSize = 12.sp, color = ems.idle)
    }

    if (selectedMode == ChargerMode.FIXED) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.charger_fixed_current), style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    Text(stringResource(R.string.charger_amps, fixedAmps), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fixedAmps.toFloat(),
                    onValueChange = { fixedAmps = it.toInt() },
                    valueRange = 6f..32f,
                    steps = 25,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.charger_amps, 6), fontSize = 11.sp, color = ems.idle)
                    Text(stringResource(R.string.charger_amps, 32), fontSize = 11.sp, color = ems.idle)
                }
            }
        }
    }

    val btn = chargerButtonState(sessionActive, pending)
    val label = when (btn.label) {
        ChargerButtonLabel.START -> stringResource(R.string.charger_start)
        ChargerButtonLabel.STOP -> stringResource(R.string.charger_stop)
        ChargerButtonLabel.STARTING -> stringResource(R.string.charger_starting)
        ChargerButtonLabel.STOPPING -> stringResource(R.string.charger_stopping)
    }
    Button(
        onClick = {
            val target = !sessionActive
            pending = target
            onSetCharging(ChargerControl(mode = selectedMode, fixedAmps = fixedAmps, charging = target))
        },
        enabled = btn.enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (btn.stopStyle) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.primary,
        ),
    ) {
        if (!btn.enabled) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
```

- [ ] **Step 6: Run the unit tests (pass)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.ChargerSceneTest" --tests "io.konektis.ems.ChargerButtonTest"`
Expected: PASS.

- [ ] **Step 7: Full app build + APK**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (incl. `ChargerUiStateTest`).

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/res/values-nl/strings.xml android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScene.kt android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt android/app/src/test/kotlin/io/konektis/ems/ChargerSceneTest.kt android/app/src/test/kotlin/io/konektis/ems/ChargerButtonTest.kt
git commit -m "feat(app): charger button reflects session + pending state; armed bolt for session-without-power"
```

---

## Task 3: Manual verification (device/emulator)

**Files:** none.

- [ ] **Step 1:** Background the app for >30 s, then resume → the connection banner clears (reconnects) within ~1 s rather than waiting out the backoff.
- [ ] **Step 2:** With a car connected, press **Start** → button immediately shows "Starting…" (disabled + spinner), then settles to **STOP** once the server echoes the session. If the car declines to draw, the button stays **STOP**, the scene shows the dim "armed" bolt, and the status reads "Session active — car not drawing".
- [ ] **Step 3:** Press **Stop** → "Stopping…" then **START**; the scene's bolt turns off.

---

## Self-Review Notes

- **Spec coverage:** Part A (reconnect) → Task 1; Part B button=session + pending → Task 2 (`chargerButtonState`, `pending` + `LaunchedEffect` clear/timeout); scene bolt modes (off/armed/charging) → Task 2 (`chargerSceneSpec(uiState, sessionActive)` + `Bolt`); hero value/status matrix incl. "session active — car not drawing" → `ChargerHero`; new strings → Task 2 Step 1; testable pure helpers → `chargerSceneSpec`/`chargerButtonState` tests.
- **Type consistency:** `ChargerSceneSpec(showCar, carDimmed, bolt: BoltMode)` + `chargerSceneSpec(uiState, sessionActive)` used identically in the test and `ChargerHero`; `chargerButtonState(sessionActive, pending)` + `ChargerButtonLabel` consistent across the helper, test, and `ChargerControls`; `ChargerHero(uiState, chargerW, sessionActive)` matches its call site; `ChargerControls(control, mode, onSetCharging)` matches its call site (the `isCharging` param is removed).
- **Build-green ordering:** Task 1 self-contained; Task 2's pure-fn signature changes, composable, screen, strings, and tests all land together so the app compiles at the task boundary.
- **No placeholders:** full code in every step.
