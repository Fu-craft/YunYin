package com.yunyin.music.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Apple system palette.
 *
 * Values follow the iOS Human Interface Guidelines system colors (light and dark
 * variants) so the app inherits the same neutral gray scale and accent hues that
 * first-party apps use. Accents are deliberately few.
 */
object AppleColors {
    // Accents
    val systemRed = Color(0xFFFF3B30)
    val systemPink = Color(0xFFFF2D55)
    val systemOrange = Color(0xFFFF9500)
    val systemYellow = Color(0xFFFFCC00)
    val systemGreen = Color(0xFF34C759)
    val systemTeal = Color(0xFF5AC8FA)
    val systemBlue = Color(0xFF007AFF)
    val systemIndigo = Color(0xFF5856D6)
    val systemPurple = Color(0xFFAF52DE)

    /** Apple Music's signature red. */
    val musicRed = Color(0xFFFA2D48)

    // Neutral label / background scale (light)
    val labelLight = Color(0xFF000000)
    val secondaryLabelLight = Color(0x993C3C43)
    val tertiaryLabelLight = Color(0x4D3C3C43)
    val systemBackgroundLight = Color(0xFFFFFFFF)
    val secondarySystemBackgroundLight = Color(0xFFF2F2F7)
    val tertiarySystemBackgroundLight = Color(0xFFFFFFFF)
    val separatorLight = Color(0x493C3C43)
    val opaqueSeparatorLight = Color(0xFFC6C6C8)

    // Neutral label / background scale (dark)
    val labelDark = Color(0xFFFFFFFF)
    val secondaryLabelDark = Color(0x99EBEBF5)
    val tertiaryLabelDark = Color(0x4CEBEBF5)
    val systemBackgroundDark = Color(0xFF000000)
    val secondarySystemBackgroundDark = Color(0xFF1C1C1E)
    val tertiarySystemBackgroundDark = Color(0xFF2C2C2E)
    val separatorDark = Color(0x99545458)
    val opaqueSeparatorDark = Color(0xFF38383A)

    // iOS grouped-list background
    val groupedBackgroundLight = Color(0xFFF2F2F7)
    val groupedBackgroundDark = Color(0xFF000000)
}

/**
 * Semantic colors resolved for the active appearance.
 *
 * Kept as a plain data class rather than Material's [androidx.compose.material3.ColorScheme]
 * because the app follows Apple's far smaller semantic set (label / secondary label /
 * separator / grouped background) instead of Material roles.
 */
data class AppPalette(
    val isDark: Boolean,
    val accent: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val background: Color,
    val secondaryBackground: Color,
    val tertiaryBackground: Color,
    val separator: Color,
    /** Fill for translucent "glass" chrome, tinted by the current backdrop. */
    val glass: Color,
    val glassBorder: Color,
)

fun lightPalette(accent: Color = AppleColors.musicRed) = AppPalette(
    isDark = false,
    accent = accent,
    label = AppleColors.labelLight,
    secondaryLabel = AppleColors.secondaryLabelLight,
    tertiaryLabel = AppleColors.tertiaryLabelLight,
    background = AppleColors.systemBackgroundLight,
    secondaryBackground = AppleColors.secondarySystemBackgroundLight,
    tertiaryBackground = AppleColors.tertiarySystemBackgroundLight,
    separator = AppleColors.separatorLight,
    glass = Color(0x66FFFFFF),
    glassBorder = Color(0x33FFFFFF),
)

fun darkPalette(accent: Color = AppleColors.musicRed) = AppPalette(
    isDark = true,
    accent = accent,
    label = AppleColors.labelDark,
    secondaryLabel = AppleColors.secondaryLabelDark,
    tertiaryLabel = AppleColors.tertiaryLabelDark,
    background = AppleColors.systemBackgroundDark,
    secondaryBackground = AppleColors.secondarySystemBackgroundDark,
    tertiaryBackground = AppleColors.tertiarySystemBackgroundDark,
    separator = AppleColors.separatorDark,
    glass = Color(0x33FFFFFF),
    glassBorder = Color(0x26FFFFFF),
)
