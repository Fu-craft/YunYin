package com.yunyin.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

    // A real Material color scheme, matching the chosen appearance.
    //
    // This was previously omitted, so `MaterialTheme` fell back to its *light* defaults regardless of
    // the app's appearance. Consumers that read the scheme directly — most visibly the default
    // Material ripple, which is drawn in `onSurface` — therefore painted a near-black press
    // highlight everywhere. On the app's dark screens that read as a black rectangle appearing under
    // the touched control.
    //
    // Supplying the scheme fixes that at the source for every default-indicating component, rather
    // than patching the ripple per call site. The palette below is the same neutral scale the app
    // already uses, so nothing else about the UI changes.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.background,
            onBackground = palette.label,
            surface = palette.secondaryBackground,
            onSurface = palette.label,
            surfaceVariant = palette.tertiaryBackground,
            onSurfaceVariant = palette.secondaryLabel,
            outline = palette.separator,
            error = AppleColors.systemRed,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.background,
            onBackground = palette.label,
            surface = palette.secondaryBackground,
            onSurface = palette.label,
            surfaceVariant = palette.tertiaryBackground,
            onSurfaceVariant = palette.secondaryLabel,
            outline = palette.separator,
            error = AppleColors.systemRed,
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = appleTypography(),
            content = content,
        )
    }
}
