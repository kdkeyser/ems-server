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
