# Android EMS UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the rough default-Material Android UI (bare theme, emoji icons, hand-rolled topology) with a slick, cohesive Material 3 interface: a proper light+dark theme, Material vector icons, and an SMA-style energy-flow diagram, across all four screens.

**Architecture:** A new `ui/theme/` package supplies the M3 color scheme, typography, shapes, and a semantic `EmsColors` palette via a `CompositionLocal`. Pure, framework-free helper functions (formatting, flow direction, geometry) carry all the testable logic and are unit-tested with TDD. Composables consume those helpers and the theme. Screens are rewritten one at a time so the build stays green; the old `TopologyView.kt` and `DeviceNode.kt` are deleted only at the end once nothing references them.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (`androidx.compose.material3`), `material-icons-extended`, JUnit (`kotlin.test`) for the pure-logic unit tests.

---

## Conventions

- All commands run from the `android/` directory unless noted: `cd /home/koen/Code/ems-server/android`.
- Run a single unit test class: `./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.<ClassName>"`
- Compile main sources (fast visual-code check): `./gradlew :app:compileDebugKotlin`
- Run all unit tests: `./gradlew :app:testDebugUnitTest`
- Power sign convention (from `StatusState`): negative = producing/exporting, positive = consuming/importing.
- This is a UI redesign: pure visual composables are verified by compilation; only framework-free helpers get unit tests.

---

## File Structure

**Create:**
- `app/src/main/kotlin/io/konektis/ems/ui/components/PowerFormat.kt` — pure formatting + power-sign logic
- `app/src/main/kotlin/io/konektis/ems/ui/components/FlowMath.kt` — pure geometry (segment insets, SoC fraction, flow direction)
- `app/src/main/kotlin/io/konektis/ems/ui/theme/Color.kt` — M3 light/dark `ColorScheme` + `EmsColors` + `LocalEmsColors`
- `app/src/main/kotlin/io/konektis/ems/ui/theme/Type.kt` — `EmsTypography`
- `app/src/main/kotlin/io/konektis/ems/ui/theme/Shape.kt` — `EmsShapes`
- `app/src/main/kotlin/io/konektis/ems/ui/theme/Theme.kt` — `EmsTheme {}` + `@Composable powerColor(sign)`
- `app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt` — Material icon mapping
- `app/src/main/kotlin/io/konektis/ems/ui/components/IconTile.kt` — rounded tonal icon tile
- `app/src/main/kotlin/io/konektis/ems/ui/components/EnergyFlowDiagram.kt` — the flow visualization (replaces `TopologyView`)
- `app/src/main/kotlin/io/konektis/ems/ui/components/DeviceCard.kt` — device status card
- `app/src/main/kotlin/io/konektis/ems/ui/components/StatusHero.kt` — shared status hero card
- `app/src/test/kotlin/io/konektis/ems/PowerFormatTest.kt`
- `app/src/test/kotlin/io/konektis/ems/FlowMathTest.kt`

**Modify:**
- `app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt` — wrap in `EmsTheme`
- `app/src/main/kotlin/io/konektis/ems/ui/overview/OverviewScreen.kt` — full rewrite
- `app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt` — full rewrite
- `app/src/main/kotlin/io/konektis/ems/ui/heatpump/HeatPumpScreen.kt` — full rewrite
- `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt` — full rewrite
- `app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt` — nav polish + pass state to HeatPump

**Delete (final task):**
- `app/src/main/kotlin/io/konektis/ems/ui/components/TopologyView.kt`
- `app/src/main/kotlin/io/konektis/ems/ui/components/DeviceNode.kt`

---

## Task 1: Pure helpers — formatting, power sign, geometry

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/PowerFormat.kt`
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/FlowMath.kt`
- Test: `app/src/test/kotlin/io/konektis/ems/PowerFormatTest.kt`
- Test: `app/src/test/kotlin/io/konektis/ems/FlowMathTest.kt`

- [ ] **Step 1: Write the failing tests for PowerFormat**

Create `app/src/test/kotlin/io/konektis/ems/PowerFormatTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.ui.components.PowerSign
import io.konektis.ems.ui.components.flowSign
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.components.powerSign
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerFormatTest {

    @Test fun `formatWatts null is em dash`() = assertEquals("—", formatWatts(null))

    @Test fun `formatWatts under 1000 shows watts`() = assertEquals("600 W", formatWatts(600))

    @Test fun `formatWatts negative uses magnitude`() = assertEquals("800 W", formatWatts(-800))

    @Test fun `formatWatts 1000 and over shows kilowatts`() {
        assertEquals("3.2 kW", formatWatts(3200))
        assertEquals("1.0 kW", formatWatts(1000))
    }

    @Test fun `powerSign zero or null is idle`() {
        assertEquals(PowerSign.IDLE, powerSign(null, positiveIsConsumption = true))
        assertEquals(PowerSign.IDLE, powerSign(0, positiveIsConsumption = true))
    }

    @Test fun `powerSign solar positive produces`() {
        // solar reports positive when producing; positiveIsConsumption = false
        assertEquals(PowerSign.PRODUCING, powerSign(3200, positiveIsConsumption = false))
    }

    @Test fun `powerSign grid import consumes, export produces`() {
        assertEquals(PowerSign.CONSUMING, powerSign(600, positiveIsConsumption = true))
        assertEquals(PowerSign.PRODUCING, powerSign(-600, positiveIsConsumption = true))
    }

    @Test fun `flowSign maps direction and favorability`() {
        assertEquals(PowerSign.IDLE, flowSign(io.konektis.ems.ui.components.FlowDirection.NONE, forwardIsFavorable = true))
        assertEquals(PowerSign.PRODUCING, flowSign(io.konektis.ems.ui.components.FlowDirection.FORWARD, forwardIsFavorable = true))
        assertEquals(PowerSign.CONSUMING, flowSign(io.konektis.ems.ui.components.FlowDirection.FORWARD, forwardIsFavorable = false))
        assertEquals(PowerSign.CONSUMING, flowSign(io.konektis.ems.ui.components.FlowDirection.REVERSE, forwardIsFavorable = true))
    }
}
```

- [ ] **Step 2: Write the failing tests for FlowMath**

Create `app/src/test/kotlin/io/konektis/ems/FlowMathTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.ui.components.FlowDirection
import io.konektis.ems.ui.components.Pt
import io.konektis.ems.ui.components.flowDirection
import io.konektis.ems.ui.components.insetSegment
import io.konektis.ems.ui.components.socFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowMathTest {

    @Test fun `insetSegment shortens a horizontal segment at both ends`() {
        val (s, e) = insetSegment(Pt(0f, 0f), Pt(100f, 0f), startInset = 20f, endInset = 30f)
        assertEquals(20f, s.x, 0.01f); assertEquals(0f, s.y, 0.01f)
        assertEquals(70f, e.x, 0.01f); assertEquals(0f, e.y, 0.01f)
    }

    @Test fun `insetSegment on zero-length segment returns endpoints unchanged`() {
        val (s, e) = insetSegment(Pt(5f, 5f), Pt(5f, 5f), 10f, 10f)
        assertEquals(5f, s.x, 0.01f); assertEquals(5f, e.y, 0.01f)
    }

    @Test fun `socFraction clamps and normalizes`() {
        assertEquals(0f, socFraction(null), 0.001f)
        assertEquals(0f, socFraction(-5), 0.001f)
        assertEquals(0.62f, socFraction(62), 0.001f)
        assertEquals(1f, socFraction(150), 0.001f)
    }

    @Test fun `flowDirection none when zero or null`() {
        assertEquals(FlowDirection.NONE, flowDirection(null, positiveIsForward = true))
        assertEquals(FlowDirection.NONE, flowDirection(0, positiveIsForward = true))
    }

    @Test fun `flowDirection forward and reverse by sign`() {
        assertEquals(FlowDirection.FORWARD, flowDirection(600, positiveIsForward = true))
        assertEquals(FlowDirection.REVERSE, flowDirection(-600, positiveIsForward = true))
        // solar: positive means producing → solar→house is forward, so positiveIsForward = true here too
        assertEquals(FlowDirection.FORWARD, flowDirection(3200, positiveIsForward = true))
    }

    @Test fun `houseLoadW sums production import and discharge, nulls as zero`() {
        // convention: solar positive = production, grid positive = import, battery positive = charging
        // load = solar + grid - battery
        assertEquals(0, io.konektis.ems.ui.components.houseLoadW(null, null, null))
        assertEquals(3800, io.konektis.ems.ui.components.houseLoadW(3200, 600, 0))
        // battery charging (positive) reduces what the house itself draws
        assertEquals(2700, io.konektis.ems.ui.components.houseLoadW(3200, 600, 1100))
        // battery discharging (negative) adds to house supply
        assertEquals(4900, io.konektis.ems.ui.components.houseLoadW(3200, 600, -1100))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.PowerFormatTest" --tests "io.konektis.ems.FlowMathTest"`
Expected: FAIL — unresolved references (`formatWatts`, `insetSegment`, etc. do not exist yet).

- [ ] **Step 4: Implement PowerFormat.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/PowerFormat.kt`:

```kotlin
package io.konektis.ems.ui.components

import kotlin.math.abs

/** Formats watts: null → "—", < 1000 → "600 W", ≥ 1000 → "3.2 kW". Uses magnitude. */
fun formatWatts(w: Int?): String {
    if (w == null) return "—"
    val a = abs(w)
    return if (a >= 1000) "${"%.1f".format(a / 1000f)} kW" else "$a W"
}

/** Whether a device's power reads as producing, consuming, or idle. */
enum class PowerSign { PRODUCING, CONSUMING, IDLE }

/**
 * @param positiveIsConsumption true for grid/battery (positive = importing/charging),
 *        false for solar (positive = producing).
 */
fun powerSign(w: Int?, positiveIsConsumption: Boolean): PowerSign {
    if (w == null || w == 0) return PowerSign.IDLE
    val consuming = positiveIsConsumption == (w > 0)
    return if (consuming) PowerSign.CONSUMING else PowerSign.PRODUCING
}

/** Direction a flow arrow points along a from→to segment. */
enum class FlowDirection { FORWARD, REVERSE, NONE }

/** Maps a drawn flow direction to a PowerSign for coloring, given whether forward is the favorable direction. */
fun flowSign(direction: FlowDirection, forwardIsFavorable: Boolean): PowerSign = when (direction) {
    FlowDirection.NONE -> PowerSign.IDLE
    FlowDirection.FORWARD -> if (forwardIsFavorable) PowerSign.PRODUCING else PowerSign.CONSUMING
    FlowDirection.REVERSE -> if (forwardIsFavorable) PowerSign.CONSUMING else PowerSign.PRODUCING
}
```

- [ ] **Step 5: Implement FlowMath.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/FlowMath.kt`:

```kotlin
package io.konektis.ems.ui.components

import kotlin.math.hypot

/** Plain 2D point, framework-free so it is unit-testable. */
data class Pt(val x: Float, val y: Float)

/**
 * Returns the sub-segment of from→to, shortened by [startInset] at the start
 * and [endInset] at the end. Used to keep arrows clear of the icons at each node.
 */
fun insetSegment(from: Pt, to: Pt, startInset: Float, endInset: Float): Pair<Pt, Pt> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len == 0f) return from to to
    val ux = dx / len
    val uy = dy / len
    val s = Pt(from.x + ux * startInset, from.y + uy * startInset)
    val e = Pt(to.x - ux * endInset, to.y - uy * endInset)
    return s to e
}

/** Battery state-of-charge as a 0f..1f fraction, clamped. null → 0f. */
fun socFraction(percent: Int?): Float {
    if (percent == null) return 0f
    return percent.coerceIn(0, 100) / 100f
}

/**
 * Direction of flow along a from→to segment.
 * @param positiveIsForward true when a positive power value means flow runs from→to.
 */
fun flowDirection(powerW: Int?, positiveIsForward: Boolean): FlowDirection {
    if (powerW == null || powerW == 0) return FlowDirection.NONE
    val forward = positiveIsForward == (powerW > 0)
    return if (forward) FlowDirection.FORWARD else FlowDirection.REVERSE
}

/**
 * Total power consumed by the house, derived from the energy balance at the house bus.
 *
 * Convention (as observed in this app's StatusState): solar positive = production,
 * grid positive = import, battery positive = charging. Then:
 *   houseLoad = solarProduction + gridImport + batteryDischarge
 *             = solar + grid - battery
 * Nulls are treated as 0. If the solar sign turns out inverted on real hardware,
 * this is the single place to flip it.
 */
fun houseLoadW(solarW: Int?, gridW: Int?, batteryW: Int?): Int =
    (solarW ?: 0) + (gridW ?: 0) - (batteryW ?: 0)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.konektis.ems.PowerFormatTest" --tests "io.konektis.ems.FlowMathTest"`
Expected: PASS (all tests green).

- [ ] **Step 7: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/PowerFormat.kt \
        android/app/src/main/kotlin/io/konektis/ems/ui/components/FlowMath.kt \
        android/app/src/test/kotlin/io/konektis/ems/PowerFormatTest.kt \
        android/app/src/test/kotlin/io/konektis/ems/FlowMathTest.kt
git commit -m "feat(android): add pure power-format and flow-geometry helpers with tests"
```

---

## Task 2: Theme foundation (Color, Type, Shape, Theme) + MainActivity

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/io/konektis/ems/ui/theme/Type.kt`
- Create: `app/src/main/kotlin/io/konektis/ems/ui/theme/Shape.kt`
- Create: `app/src/main/kotlin/io/konektis/ems/ui/theme/Theme.kt`
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt`

- [ ] **Step 1: Create Color.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/theme/Color.kt`:

```kotlin
package io.konektis.ems.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---- Material 3 base schemes (green-seeded, fixed — no dynamic color) ----

val LightColors = lightColorScheme(
    primary            = Color(0xFF1E6C45),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFA6F2C2),
    onPrimaryContainer = Color(0xFF00210F),
    secondary          = Color(0xFF4E6355),
    onSecondary        = Color(0xFFFFFFFF),
    background         = Color(0xFFF6FBF4),
    onBackground       = Color(0xFF171D18),
    surface            = Color(0xFFF6FBF4),
    onSurface          = Color(0xFF171D18),
    surfaceVariant     = Color(0xFFDCE5DC),
    onSurfaceVariant   = Color(0xFF404943),
    outline            = Color(0xFF707972),
    outlineVariant     = Color(0xFFC0C9C0),
    error              = Color(0xFFBA1A1A),
    onError            = Color(0xFFFFFFFF),
)

val DarkColors = darkColorScheme(
    primary            = Color(0xFF8AD6A7),
    onPrimary          = Color(0xFF00391E),
    primaryContainer   = Color(0xFF02522F),
    onPrimaryContainer = Color(0xFFA6F2C2),
    secondary          = Color(0xFFB4CCBB),
    onSecondary        = Color(0xFF203528),
    background         = Color(0xFF0E1411),
    onBackground       = Color(0xFFDEE4DD),
    surface            = Color(0xFF0E1411),
    onSurface          = Color(0xFFDEE4DD),
    surfaceVariant     = Color(0xFF404943),
    onSurfaceVariant   = Color(0xFFBFC9C0),
    outline            = Color(0xFF8A938B),
    outlineVariant     = Color(0xFF404943),
    error              = Color(0xFFFFB4AB),
    onError            = Color(0xFF690005),
)

// ---- Semantic energy palette (not expressible as M3 roles) ----

data class EmsColors(
    val production: Color,    // green: producing / favorable
    val consumption: Color,   // red: consuming / unfavorable
    val idle: Color,          // neutral grey
    val online: Color,        // status dot when online
    val tileBg: Color,        // default icon-tile background
    val houseTileBg: Color,   // house anchor tile background
    val onTile: Color,        // default icon color on a tile
)

val LightEmsColors = EmsColors(
    production  = Color(0xFF15803D),
    consumption = Color(0xFFDC2626),
    idle        = Color(0xFF6B7280),
    online      = Color(0xFF15803D),
    tileBg      = Color(0xFFE6EFE7),
    houseTileBg = Color(0xFFD6E6F5),
    onTile      = Color(0xFF374151),
)

val DarkEmsColors = EmsColors(
    production  = Color(0xFF34D399),
    consumption = Color(0xFFF87171),
    idle        = Color(0xFF7C8A9B),
    online      = Color(0xFF4ADE80),
    tileBg      = Color(0xFF1A2230),
    houseTileBg = Color(0xFF173049),
    onTile      = Color(0xFFCBD5E1),
)

/** Provides the active EmsColors. Defaults to dark; EmsTheme overrides per system setting. */
val LocalEmsColors = staticCompositionLocalOf { DarkEmsColors }
```

- [ ] **Step 2: Create Type.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/theme/Type.kt`:

```kotlin
package io.konektis.ems.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material 3 type scale with slightly tightened headings for a denser dashboard feel. */
val EmsTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.0.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)
```

- [ ] **Step 3: Create Shape.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/theme/Shape.kt`:

```kotlin
package io.konektis.ems.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val EmsShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)
```

- [ ] **Step 4: Create Theme.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/theme/Theme.kt`:

```kotlin
package io.konektis.ems.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import io.konektis.ems.ui.components.PowerSign

@Composable
fun EmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val emsColors = if (darkTheme) DarkEmsColors else LightEmsColors
    CompositionLocalProvider(LocalEmsColors provides emsColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EmsTypography,
            shapes = EmsShapes,
            content = content
        )
    }
}

/** Resolves a PowerSign to its semantic color from the active EmsColors. */
@Composable
fun powerColor(sign: PowerSign): Color {
    val c = LocalEmsColors.current
    return when (sign) {
        PowerSign.PRODUCING -> c.production
        PowerSign.CONSUMING -> c.consumption
        PowerSign.IDLE -> c.idle
    }
}
```

- [ ] **Step 5: Wire MainActivity to EmsTheme**

Replace the entire contents of `app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt`:

```kotlin
package io.konektis.ems.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.theme.EmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmsApplication
        setContent {
            EmsTheme {
                EmsNavHost(app)
            }
        }
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/theme/ \
        android/app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt
git commit -m "feat(android): add Material 3 theme (light+dark) with semantic energy palette"
```

---

## Task 3: Icon mapping + IconTile component

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt`
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/IconTile.kt`

- [ ] **Step 1: Create EmsIcons.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt`:

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector

/** Central mapping of energy nodes/devices to Material vector icons (no emoji). */
object EmsIcons {
    val Solar: ImageVector = Icons.Filled.SolarPower
    val Grid: ImageVector = Icons.Filled.Bolt
    val House: ImageVector = Icons.Filled.Home
    val Battery: ImageVector = Icons.Filled.BatteryChargingFull
    val Charger: ImageVector = Icons.Filled.EvStation
    val HeatPump: ImageVector = Icons.Filled.Thermostat
}

/** Maps a device's `category` string (from StatusState) to an icon; falls back to Bolt. */
fun iconForCategory(category: String): ImageVector = when (category.lowercase()) {
    "solar" -> EmsIcons.Solar
    "grid" -> EmsIcons.Grid
    "battery" -> EmsIcons.Battery
    "charger", "car_charger" -> EmsIcons.Charger
    "heatpump", "heat_pump" -> EmsIcons.HeatPump
    else -> EmsIcons.Grid
}
```

- [ ] **Step 2: Create IconTile.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/IconTile.kt`:

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.konektis.ems.ui.theme.LocalEmsColors

/** A rounded tonal square containing a centered vector icon. */
@Composable
fun IconTile(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tileColor: Color = LocalEmsColors.current.tileBg,
    iconColor: Color = LocalEmsColors.current.onTile,
    cornerRadius: Dp = 16.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        color = tileColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `Icons.Filled.SolarPower` fails to resolve, the `material-icons-extended` dependency is missing — confirm `implementation(libs.compose.material.icons.extended)` is present in `app/build.gradle.kts`.)

- [ ] **Step 4: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/EmsIcons.kt \
        android/app/src/main/kotlin/io/konektis/ems/ui/components/IconTile.kt
git commit -m "feat(android): add Material icon mapping and IconTile component"
```

---

## Task 4: EnergyFlowDiagram (replaces TopologyView)

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/EnergyFlowDiagram.kt`

Note: `TopologyView.kt` stays in place for now (still referenced by the not-yet-rewritten `OverviewScreen`). It is deleted in Task 12.

- [ ] **Step 1: Create EnergyFlowDiagram.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/EnergyFlowDiagram.kt`:

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.theme.LocalEmsColors
import io.konektis.ems.ui.theme.powerColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private data class NodeAnchor(val x: Float, val y: Float) {
    fun bias() = BiasAlignment(x * 2f - 1f, y * 2f - 1f)
    fun pt(w: Float, h: Float) = Pt(w * x, h * y)
}

private val ANCHOR_SOLAR   = NodeAnchor(0.50f, 0.16f)
private val ANCHOR_HOUSE    = NodeAnchor(0.50f, 0.54f)
private val ANCHOR_GRID    = NodeAnchor(0.16f, 0.86f)
private val ANCHOR_BATTERY = NodeAnchor(0.84f, 0.86f)

/**
 * SMA-style energy flow: nodes carry their own values beneath their icons; the
 * connecting lines are short, thin, solid, color-coded, with a small arrowhead.
 */
@Composable
fun EnergyFlowDiagram(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val produce = ems.production
    val consume = ems.consumption
    val density = LocalDensity.current
    val inset = with(density) { 46.dp.toPx() } // keep arrows clear of icons

    // Pre-resolve flow directions + colors (composable calls must be outside DrawScope).
    val solarDir = flowDirection(state?.totalSolarW, positiveIsForward = true)
    val gridDir = flowDirection(state?.gridW, positiveIsForward = true)
    val batteryDir = flowDirection(state?.batteryW, positiveIsForward = true)
    val solarColor = powerColor(flowSign(solarDir, forwardIsFavorable = true))
    val gridColor = powerColor(flowSign(gridDir, forwardIsFavorable = false))
    val batteryColor = powerColor(flowSign(batteryDir, forwardIsFavorable = false))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val house = ANCHOR_HOUSE.pt(w, h)
            drawFlow(ANCHOR_SOLAR.pt(w, h), house, solarDir, solarColor, inset)
            drawFlow(ANCHOR_GRID.pt(w, h), house, gridDir, gridColor, inset)
            drawFlow(house, ANCHOR_BATTERY.pt(w, h), batteryDir, batteryColor, inset)
        }

        FlowNode(
            icon = EmsIcons.Solar, caption = "Solar",
            value = formatWatts(state?.totalSolarW),
            valueColor = powerColor(powerSign(state?.totalSolarW, positiveIsConsumption = false)),
            modifier = Modifier.align(ANCHOR_SOLAR.bias()),
        )
        FlowNode(
            icon = EmsIcons.House, caption = "Home",
            value = if (state == null) "—"
                    else formatWatts(houseLoadW(state.totalSolarW, state.gridW, state.batteryW)),
            valueColor = ems.onTile,
            big = true, tileColor = ems.houseTileBg,
            modifier = Modifier.align(ANCHOR_HOUSE.bias()),
        )
        FlowNode(
            icon = EmsIcons.Grid,
            caption = if ((state?.gridW ?: 0) < 0) "Grid · export" else "Grid · import",
            value = formatWatts(state?.gridW),
            valueColor = powerColor(powerSign(state?.gridW, positiveIsConsumption = true)),
            modifier = Modifier.align(ANCHOR_GRID.bias()),
        )
        FlowNode(
            icon = EmsIcons.Battery,
            caption = state?.batteryCharge?.let { "Battery · $it%" } ?: "Battery",
            value = formatWatts(state?.batteryW),
            valueColor = powerColor(powerSign(state?.batteryW, positiveIsConsumption = true)),
            modifier = Modifier.align(ANCHOR_BATTERY.bias()),
        )
    }
}

@Composable
private fun FlowNode(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    caption: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    tileColor: Color = LocalEmsColors.current.tileBg,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconTile(
            icon = icon,
            contentDescription = caption,
            size = if (big) 64.dp else 50.dp,
            tileColor = tileColor,
        )
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = LocalEmsColors.current.idle, fontSize = 9.sp)
    }
}

/** Draws one short, thin, solid arrow with a small head, oriented by [dir]. */
private fun DrawScope.drawFlow(from: Pt, to: Pt, dir: FlowDirection, color: Color, inset: Float) {
    if (dir == FlowDirection.NONE) return
    val (s, e) = insetSegment(from, to, inset, inset)
    val start = if (dir == FlowDirection.FORWARD) s else e
    val end = if (dir == FlowDirection.FORWARD) e else s
    val stroke = 1.8.dp.toPx()
    val startO = Offset(start.x, start.y)
    val endO = Offset(end.x, end.y)
    drawLine(color, startO, endO, stroke)
    val angle = atan2((endO.y - startO.y), (endO.x - startO.x))
    val headLen = 9.dp.toPx()
    val a = 0.5f
    drawLine(color, endO, Offset(endO.x - cos(angle - a) * headLen, endO.y - sin(angle - a) * headLen), stroke)
    drawLine(color, endO, Offset(endO.x - cos(angle + a) * headLen, endO.y - sin(angle + a) * headLen), stroke)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/EnergyFlowDiagram.kt
git commit -m "feat(android): add EnergyFlowDiagram (SMA-style, values under icons)"
```

---

## Task 5: DeviceCard component

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/DeviceCard.kt`

- [ ] **Step 1: Create DeviceCard.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/DeviceCard.kt`:

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.ui.theme.LocalEmsColors
import io.konektis.ems.ui.theme.powerColor

@Composable
fun DeviceCard(device: DeviceStatus, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val health = device.health
    val online = health is DeviceHealth.Online
    val positiveIsConsumption = device.category.lowercase() != "solar"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (online) 1f else 0.6f),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = iconForCategory(device.category),
                contentDescription = device.category,
                size = 40.dp,
                cornerRadius = 12.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(device.name, fontSize = 14.sp)
                val subtitle = when (health) {
                    is DeviceHealth.Online -> "Online" + (health.extraInfo?.let { " · $it" } ?: "")
                    is DeviceHealth.Offline -> health.lastError?.let { "Offline · $it" } ?: "Offline"
                }
                val subtitleColor = if (online) ems.idle else ems.consumption
                Text(subtitle, fontSize = 11.sp, color = subtitleColor)
            }
            if (health is DeviceHealth.Online) {
                Text(
                    text = formatWatts(health.powerW),
                    fontSize = 14.sp,
                    color = powerColor(powerSign(health.powerW, positiveIsConsumption)),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/DeviceCard.kt
git commit -m "feat(android): add DeviceCard component"
```

---

## Task 6: OverviewScreen rewrite

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/overview/OverviewScreen.kt` (full replace)

- [ ] **Step 1: Replace OverviewScreen.kt**

Replace the entire contents of `app/src/main/kotlin/io/konektis/ems/ui/overview/OverviewScreen.kt`:

```kotlin
package io.konektis.ems.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.DeviceCard
import io.konektis.ems.ui.components.EnergyFlowDiagram
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun OverviewScreen(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EnergyFlowDiagram(state = state)

        val devices = state?.devices.orEmpty()
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Waiting for data…", fontSize = 14.sp, color = ems.idle)
            }
        } else {
            Text(
                "DEVICES",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = ems.idle,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
            devices.forEach { DeviceCard(it) }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/overview/OverviewScreen.kt
git commit -m "feat(android): rewrite OverviewScreen with flow diagram + device cards"
```

---

## Task 7: StatusHero component

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/ui/components/StatusHero.kt`

- [ ] **Step 1: Create StatusHero.kt**

Create `app/src/main/kotlin/io/konektis/ems/ui/components/StatusHero.kt`:

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.ui.theme.LocalEmsColors

/** Large centered status card: icon tile, big value, and a status line with a colored dot. */
@Composable
fun StatusHero(
    icon: ImageVector,
    value: String,
    valueColor: Color,
    statusText: String,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconTile(icon = icon, contentDescription = statusText, size = 60.dp, cornerRadius = 18.dp)
            Text(value, color = valueColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", fontSize = 10.sp, color = if (online) ems.online else ems.consumption)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = ems.idle)
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/components/StatusHero.kt
git commit -m "feat(android): add shared StatusHero component"
```

---

## Task 8: ChargerScreen rewrite (segmented mode + slider + action button)

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt` (full replace)

- [ ] **Step 1: Replace ChargerScreen.kt**

Replace the entire contents of `app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`:

```kotlin
package io.konektis.ems.ui.charger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

private enum class ChargingMode { SOLAR, MANUAL }

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    onSetCharging: (ChargingState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    val chargerW = statusState?.chargerW
    val isCharging = chargerW != null && chargerW > 0
    val isAuthenticated = controlState is ControlState.Authenticated
    var chargingMode by remember { mutableStateOf(ChargingMode.SOLAR) }
    var manualPower by remember { mutableIntStateOf(3680) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusHero(
            icon = EmsIcons.Charger,
            value = when {
                chargerW == null -> "—"
                isCharging -> formatWatts(chargerW)
                else -> "Idle"
            },
            valueColor = if (isCharging) ems.consumption else ems.idle,
            statusText = if (isCharging) "Charging · Webasto" else "Not charging",
            online = chargerW != null,
        )

        if (isAuthenticated) {
            Text("MODE", style = MaterialTheme.typography.labelSmall, color = ems.idle)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = chargingMode == ChargingMode.SOLAR,
                    onClick = { chargingMode = ChargingMode.SOLAR },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Solar surplus") }
                SegmentedButton(
                    selected = chargingMode == ChargingMode.MANUAL,
                    onClick = { chargingMode = ChargingMode.MANUAL },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Fixed power") }
            }

            if (chargingMode == ChargingMode.MANUAL) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Max power", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                            Text(formatWatts(manualPower), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = manualPower.toFloat(),
                            onValueChange = { manualPower = it.toInt() },
                            valueRange = 1440f..7680f,
                            steps = 25,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("1.4 kW", fontSize = 11.sp, color = ems.idle)
                            Text("7.7 kW", fontSize = 11.sp, color = ems.idle)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (isCharging) {
                        onSetCharging(ChargingState.NotCharging)
                    } else {
                        onSetCharging(
                            when (chargingMode) {
                                ChargingMode.SOLAR -> ChargingState.ChargingWithExcessPower
                                ChargingMode.MANUAL -> ChargingState.ChargingWithMaxPower(manualPower.toUInt())
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCharging) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    if (isCharging) "STOP CHARGING" else "START CHARGING",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                "Charger control unavailable — check credentials in Settings.",
                fontSize = 14.sp,
                color = ems.idle,
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`SingleChoiceSegmentedButtonRow` / `SegmentedButton` are stable in Material3 1.2+; if unresolved, confirm the material3 version in `libs.versions.toml` is ≥ 1.2.)

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt
git commit -m "feat(android): rewrite ChargerScreen with hero, segmented mode, slider"
```

---

## Task 9: HeatPumpScreen rewrite (display-only status)

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/heatpump/HeatPumpScreen.kt` (full replace)

- [ ] **Step 1: Replace HeatPumpScreen.kt**

Replace the entire contents of `app/src/main/kotlin/io/konektis/ems/ui/heatpump/HeatPumpScreen.kt`:

```kotlin
package io.konektis.ems.ui.heatpump

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun HeatPumpScreen(state: StatusState?, modifier: Modifier = Modifier) {
    val ems = LocalEmsColors.current
    val device = state?.devices?.firstOrNull { it.category.lowercase() in setOf("heatpump", "heat_pump") }
    val health = device?.health
    val online = health is DeviceHealth.Online
    val powerW = (health as? DeviceHealth.Online)?.powerW ?: state?.heatpumpW

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHero(
            icon = EmsIcons.HeatPump,
            value = if (online) formatWatts(powerW) else "—",
            valueColor = if (online) ems.consumption else ems.idle,
            statusText = when (health) {
                is DeviceHealth.Online -> "Online" + (health.extraInfo?.let { " · $it" } ?: "")
                is DeviceHealth.Offline -> health.lastError?.let { "Offline · $it" } ?: "Offline"
                null -> "No data"
            },
            online = online,
        )
        Text(
            "No controls available — the server exposes heat-pump status only.",
            fontSize = 12.sp,
            color = ems.idle,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/heatpump/HeatPumpScreen.kt
git commit -m "feat(android): rewrite HeatPumpScreen as display-only status"
```

---

## Task 10: SettingsScreen rewrite (grouped card)

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt` (full replace)

- [ ] **Step 1: Replace SettingsScreen.kt**

Replace the entire contents of `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt`:

```kotlin
package io.konektis.ems.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.konektis.ems.data.settings.Settings
import io.konektis.ems.ui.theme.LocalEmsColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val ems = LocalEmsColors.current
    val saved by vm.settingsFlow.collectAsState(initial = Settings())
    var serverUrl by rememberSaveable(saved.serverUrl) { mutableStateOf(saved.serverUrl) }
    var username by rememberSaveable(saved.username) { mutableStateOf(saved.username) }
    var password by rememberSaveable(saved.password) { mutableStateOf(saved.password) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("CONNECTION", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server address") },
                        placeholder = { Text("10.0.2.2:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = {
                    vm.save(Settings(serverUrl.trim(), username.trim(), password))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt
git commit -m "feat(android): rewrite SettingsScreen with grouped connection card"
```

---

## Task 11: DashboardScreen polish + wire HeatPump state

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt`

The current file passes no state to `HeatPumpScreen()` (`2 -> HeatPumpScreen()`). The rewritten
`HeatPumpScreen` now requires `state`. Also switch the tab icons to the Material mapping.

- [ ] **Step 1: Update the tab list to use EmsIcons**

In `app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt`, replace the imports block for the filled icons and the `tabs` definition.

Remove these imports:

```kotlin
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Thermostat
```

Add this import:

```kotlin
import io.konektis.ems.ui.components.EmsIcons
```

Replace the `tabs` val:

```kotlin
private val tabs = listOf(
    TabItem("Overview", EmsIcons.House),
    TabItem("Charger", EmsIcons.Charger),
    TabItem("Heat Pump", EmsIcons.HeatPump),
)
```

- [ ] **Step 2: Pass state to HeatPumpScreen**

In the same file, find:

```kotlin
                2 -> HeatPumpScreen()
```

Replace with:

```kotlin
                2 -> HeatPumpScreen(state = status)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /home/koen/Code/ems-server
git add android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt
git commit -m "feat(android): use Material icons in nav and wire HeatPump state"
```

---

## Task 12: Delete legacy components, final build & full test run

**Files:**
- Delete: `app/src/main/kotlin/io/konektis/ems/ui/components/TopologyView.kt`
- Delete: `app/src/main/kotlin/io/konektis/ems/ui/components/DeviceNode.kt`

- [ ] **Step 1: Confirm nothing references the legacy files**

Run:
```bash
cd /home/koen/Code/ems-server/android
grep -rn "TopologyView\|DeviceNode\|fmtWatts\|lineColor" app/src/main/kotlin \
  | grep -v "ui/components/TopologyView.kt" | grep -v "ui/components/DeviceNode.kt"
```
Expected: no output. These are the symbols that lived only in the legacy files. (Note: `powerColor` still exists but has moved to `ui/theme/Theme.kt` with a new signature `powerColor(sign: PowerSign)`; any stale old-signature call is caught by the compile step in Step 3.)
If any references remain, fix them to use `formatWatts` / `powerColor(powerSign(...))` from the new files before deleting.

- [ ] **Step 2: Delete the legacy files**

```bash
cd /home/koen/Code/ems-server
git rm android/app/src/main/kotlin/io/konektis/ems/ui/components/TopologyView.kt \
       android/app/src/main/kotlin/io/konektis/ems/ui/components/DeviceNode.kt
```

- [ ] **Step 3: Full compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full unit test run**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass (the pre-existing model/ViewModel/settings tests plus the new `PowerFormatTest` and `FlowMathTest`).

- [ ] **Step 5: Commit**

```bash
cd /home/koen/Code/ems-server
git add -A
git commit -m "refactor(android): remove legacy TopologyView and DeviceNode"
```

- [ ] **Step 6: Manual verification (emulator)**

Build and install on a running emulator:
```bash
cd /home/koen/Code/ems-server/android
./gradlew :app:installDebug
```
Then verify by hand against the spec:
- Overview: flow diagram shows four nodes with values **under** each icon; arrows are short/thin/solid/color-coded and never overlap icons; battery caption shows SoC; device cards render below; offline devices dimmed.
- Charger: hero + segmented mode + slider (Fixed only) + colored START/STOP button.
- Heat Pump: hero status only, "no controls" note.
- Settings: grouped card + Save.
- Toggle the emulator to **dark mode** (Settings ▸ Display) and confirm the app follows light/dark.

---

## Self-Review Notes

- **Spec coverage:** Theme foundation (Task 2) ✓; semantic energy colors (Task 2) ✓; Material icons replacing emoji (Tasks 3, 11) ✓; SMA-style Overview with values-under-icons + short/thin/solid color-coded arrows + battery SoC + device cards (Tasks 4, 5, 6) ✓; Charger segmented mode + slider + action button (Task 8) ✓; display-only Heat Pump tab (Task 9) ✓; grouped Settings card (Task 10) ✓; navigation polish (Task 11) ✓; existing tests preserved + new pure-logic tests (Tasks 1, 12) ✓.
- **Animation:** spec marked the flow animation optional/deferrable; this plan ships static arrows. A subtle pulse can be added later without structural change.
- **Type consistency:** `formatWatts`, `powerSign`/`PowerSign`, `flowSign`, `flowDirection`/`FlowDirection`, `insetSegment`/`Pt`, `socFraction`, `houseLoadW`, `powerColor(sign)`, `IconTile`, `EmsIcons`, `iconForCategory`, `StatusHero`, `DeviceCard`, `EnergyFlowDiagram` are defined once and used with consistent signatures across tasks.
- **Note on `socFraction`:** defined and tested in Task 1 for the battery SoC ring. The Task 4 diagram currently shows SoC as a caption ("Battery · 62%"); if a literal ring is desired on the battery tile, `socFraction` is ready to drive a `drawArc` sweep — left as a small enhancement to keep Task 4 focused.
```
