package com.yunyin.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalPalette = staticCompositionLocalOf { lightPalette() }

/** Corner radii, expressed once so every surface in the app stays consistent. */
object AppleShapes {
    /** Cards and artwork tiles use iOS "large" continuous corners. */
    val card: Dp = 16.dp
    val cardLarge: Dp = 20.dp
    val sheet: Dp = 24.dp
    /** Pills / capsules. */
    val pill: Dp = 999.dp
    val control: Dp = 12.dp
}

/** Convenience accessor used throughout the UI: `AppTheme.palette.accent`. */
object AppTheme {
    val palette: AppPalette
        @Composable get() = LocalPalette.current
}

/**
 * Root theme.
 *
 * Deliberately thin: it wires the SF Pro type scale and the semantic [AppPalette]
 * into the composition. Material 3 is used only as a substrate for the handful of
 * components that are easier to reuse (ripple, text fields), so the ColorScheme is
 * left at its defaults and screens read colors from [LocalPalette].
 */
@Composable
fun AmllTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Optional accent override, e.g. a color sampled from the current artwork. */
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) darkPalette(accent ?: AppleColors.musicRed)
    else lightPalette(accent ?: AppleColors.musicRed)

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            typography = appleTypography(),
            content = content,
        )
    }
}
