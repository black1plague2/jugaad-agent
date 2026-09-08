package com.jugaad.agent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val JugaadColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    primaryContainer = AccentDim,
    onPrimaryContainer = TextHi,
    secondary = Accent,
    background = Ink,
    onBackground = TextHi,
    surface = InkElevated,
    onSurface = TextHi,
    surfaceVariant = InkCard,
    onSurfaceVariant = TextMid,
    outline = InkLine,
    error = StatusCritical,
    onError = TextHi,
)

/**
 * Single, permanently-dark industrial theme. We intentionally ignore the system
 * light/dark setting — this is a shop-floor tool.
 */
@Composable
fun JugaadTheme(
    @Suppress("UNUSED_PARAMETER") systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ink.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = JugaadColors,
        typography = Typography,
        content = content,
    )
}
