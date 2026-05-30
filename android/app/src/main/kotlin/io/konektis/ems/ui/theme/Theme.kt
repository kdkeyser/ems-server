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
