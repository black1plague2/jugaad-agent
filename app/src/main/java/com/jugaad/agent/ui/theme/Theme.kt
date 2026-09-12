package com.jugaad.agent.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriControlRadius
import com.jugaad.agent.ui.common.fiori.FioriRadius
import com.jugaad.agent.ui.common.fiori.FioriTagRadius

private val JugaadColors = darkColorScheme(
    primary = FioriColors.Brand,
    onPrimary = FioriColors.TextPrimary,
    primaryContainer = FioriColors.Brand.copy(alpha = 0.16f),
    onPrimaryContainer = FioriColors.TextPrimary,
    secondary = FioriColors.Brand,
    tertiary = FioriColors.Informative,
    onTertiary = FioriColors.Background,
    background = FioriColors.Background,
    onBackground = FioriColors.TextPrimary,
    surface = FioriColors.Surface,
    onSurface = FioriColors.TextPrimary,
    surfaceVariant = FioriColors.SurfaceElevated,
    onSurfaceVariant = FioriColors.TextSecondary,
    outline = FioriColors.Hairline,
    outlineVariant = FioriColors.Hairline,
    error = FioriColors.Negative,
    onError = FioriColors.TextPrimary,
    errorContainer = FioriColors.NegativeContainer,
    onErrorContainer = FioriColors.TextPrimary,
)

/** Humane Minimalist Dark radius rule: tags 6 dp, controls 8 dp, cards 16 dp. */
private val JugaadShapes = Shapes(
    extraSmall = FioriTagRadius,
    small = FioriTagRadius,
    medium = FioriControlRadius,
    large = FioriRadius,
    extraLarge = FioriRadius,
)

/**
 * Single, permanently-dark industrial theme. We intentionally ignore the system
 * light/dark setting: this is a shop-floor tool.
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
            window.statusBarColor = FioriColors.Background.toArgb()
            window.navigationBarColor = FioriColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = JugaadColors,
        typography = Typography,
        shapes = JugaadShapes,
        content = content,
    )
}
