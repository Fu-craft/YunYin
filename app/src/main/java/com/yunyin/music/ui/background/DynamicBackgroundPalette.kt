package com.yunyin.music.ui.background

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs

/*
 * Palette construction ported from NeriPlayer (GPL-3.0).
 *
 * Source: NeriPlayer-master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt
 *   - buildDynamicBackgroundShaderPalette()
 *   - buildDynamicBackgroundPalette()
 *   - pickPaletteColor() / pickAccentPaletteColor() / ensureAccentColor()
 *   - softenPaletteColor() / isAchromaticCoverColor() / colorfulnessScore()
 *   - hslLightnessSpan() / darkenColor() / colorLuma() / hueDistanceDegrees()
 *
 * Copyright (C) 2025-2025 NeriPlayer developers, https://github.com/cwuom/NeriPlayer
 *
 * Both NeriPlayer and YunYin are licensed under the GNU General Public License v3.0. This file is a
 * port, adapted to YunYin's DynamicBackgroundPalette data class and to the AndroidX Palette built
 * from the artwork we already decode. Only the five-colour shader palette and its tone offsets are
 * ported; NeriPlayer's Coil loading, caching and Compose hosting are not.
 */

/** The five roles the shader's colour fields are built from. Mirrors NeriPlayer's enum. */
internal enum class DynamicBackgroundColorRole {
    Base,
    Accent,
    Light,
    Dark,
    Bridge,
}

/**
 * The five colours the background shader blends, plus its tone offsets.
 *
 * Roles mirror NeriPlayer's palette builder: a base, an accent, a light and a dark variant, and
 * a bridge tone that keeps neighbouring fields from clashing. AndroidX [Palette] supplies the
 * raw swatches; the softening below is what keeps a colourful cover from producing a garish
 * screen.
 */
data class DynamicBackgroundPalette(
    /** Exactly five ARGB colours, in shader field order. */
    val colors: IntArray,
    /** Saturation offset uniform; differs by theme. */
    val saturateOffset: Float,
    /** Lightness offset uniform; differs by theme and cover brightness. */
    val lightOffset: Float,
    /** Mean perceptual lightness of the base colour, 0..1 — used for UI contrast decisions. */
    val baseLuminance: Float,
    /** A representative colour for tinting UI chrome. */
    val accent: Int,
) {
    /** The five colours as the shader's `vec4[5]`, RGB in 0..1 with alpha 1. */
    fun toShaderColors(): FloatArray {
        val out = FloatArray(colors.size * 4)
        colors.forEachIndexed { index, color ->
            val offset = index * 4
            out[offset] = channel(color ushr 16 and 0xFF)
            out[offset + 1] = channel(color ushr 8 and 0xFF)
            out[offset + 2] = channel(color and 0xFF)
            out[offset + 3] = 1f
        }
        return out
    }

    private fun channel(value: Int) = value / 255f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DynamicBackgroundPalette) return false
        return colors.contentEquals(other.colors) &&
            saturateOffset == other.saturateOffset &&
            lightOffset == other.lightOffset
    }

    override fun hashCode(): Int =
        colors.contentHashCode() * 31 + saturateOffset.hashCode() * 17 + lightOffset.hashCode()

    companion object {
        private const val FALLBACK = 0xFF808080.toInt()

        /** Hue separation above which two fields are considered to fight each other. */
        private const val STRONG_HUE_CONFLICT_DEGREES = 105f

        /** Chroma below which a swatch is effectively grey (its HSL saturation is noise). */
        private const val ACHROMATIC_CHROMA_THRESHOLD = 0.06f

        /** Used before artwork loads, and if extraction fails. */
        fun fallback(isDark: Boolean = false): DynamicBackgroundPalette = buildFromPalette(null, isDark)

        /** Extracts the palette from artwork. Safe to call off the main thread. */
        fun from(bitmap: Bitmap, isDark: Boolean): DynamicBackgroundPalette {
            val palette = runCatching {
                Palette.from(bitmap)
                    // Keep the artwork's real colours; the default filters discard the
                    // swatches that actually characterise the cover.
                    .clearFilters()
                    .maximumColorCount(16)
                    .generate()
            }.getOrNull()
            return buildFromPalette(palette, isDark)
        }

        /** Direct construction from five colours; used by callers that already have a palette. */
        fun fromColors(colors: IntArray, isDark: Boolean): DynamicBackgroundPalette {
            val baseLuma = colorLuma(colors.first())
            return DynamicBackgroundPalette(
                colors = colors,
                saturateOffset = if (isDark) 0.24f else 0.16f,
                lightOffset = shaderLightOffset(baseLuma, isDark),
                baseLuminance = baseLuma,
                accent = colors.getOrElse(1) { colors.first() },
            )
        }

        // ------------------------------------------------------------ palette construction

        /**
         * NeriPlayer's role-based palette: pick a base, an accent with real chroma, a light and a
         * dark variant around it, and a bridge tone, then soften each role toward a comfortable
         * band. The result is what stops a saturated cover from producing a garish screen.
         */
        internal fun buildFromPalette(palette: Palette?, isDark: Boolean): DynamicBackgroundPalette {
            val baseColor = pickPaletteColor(
                palette?.mutedSwatch?.rgb,
                palette?.dominantSwatch?.rgb,
                palette?.darkMutedSwatch?.rgb,
                palette?.vibrantSwatch?.rgb,
            )
            val accentColor = pickAccentPaletteColor(
                baseColor,
                palette?.vibrantSwatch?.rgb,
                palette?.lightVibrantSwatch?.rgb,
                palette?.darkVibrantSwatch?.rgb,
                palette?.dominantSwatch?.rgb,
                palette?.mutedSwatch?.rgb,
            )
            val lightColor = pickPaletteColor(
                palette?.lightVibrantSwatch?.rgb,
                palette?.lightMutedSwatch?.rgb,
                accentColor,
                baseColor,
            )
            val darkColor = pickPaletteColor(
                palette?.darkVibrantSwatch?.rgb,
                palette?.darkMutedSwatch?.rgb,
                darkenColor(accentColor, if (isDark) 0.44f else 0.34f),
                palette?.mutedSwatch?.rgb,
                baseColor,
            )
            val bridgeColor = ColorUtils.blendARGB(
                ColorUtils.blendARGB(accentColor, lightColor, 0.50f),
                baseColor,
                0.24f,
            )
            val softenedColors = intArrayOf(
                softenPaletteColor(baseColor, baseColor, isDark, DynamicBackgroundColorRole.Base),
                softenPaletteColor(accentColor, baseColor, isDark, DynamicBackgroundColorRole.Accent),
                softenPaletteColor(lightColor, baseColor, isDark, DynamicBackgroundColorRole.Light),
                softenPaletteColor(darkColor, baseColor, isDark, DynamicBackgroundColorRole.Dark),
                softenPaletteColor(bridgeColor, baseColor, isDark, DynamicBackgroundColorRole.Bridge),
            )
            val baseLuma = colorLuma(softenedColors.first())
            return DynamicBackgroundPalette(
                colors = softenedColors,
                saturateOffset = if (isDark) 0.24f else 0.16f,
                lightOffset = shaderLightOffset(baseLuma, isDark),
                baseLuminance = baseLuma,
                accent = softenedColors[1],
            )
        }

        /** NeriPlayer's shader tone offsets: lightness follows the cover's own base luminance. */
        internal fun shaderLightOffset(baseLuma: Float, isDark: Boolean): Float = (
            if (isDark) {
                -0.06f + (0.12f * (baseLuma - 0.5f))
            } else {
                0.08f + (0.10f * (0.5f - baseLuma))
            }
            ).coerceIn(-0.12f, 0.12f)

        internal fun pickPaletteColor(vararg candidates: Int?): Int =
            candidates.firstOrNull { it != null && it != 0 } ?: FALLBACK

        /** Chooses the most colourful swatch above a floor, then guarantees it reads as an accent. */
        internal fun pickAccentPaletteColor(fallback: Int, vararg candidates: Int?): Int {
            val accent = candidates
                .filterNotNull()
                .filter { it != 0 }
                .maxByOrNull { colorfulnessScore(it) }
            val selected = accent?.takeIf { colorfulnessScore(it) >= 0.10f } ?: fallback
            if (isAchromaticCoverColor(selected) && isAchromaticCoverColor(fallback)) {
                return selected
            }
            return ensureAccentColor(selected, fallback)
        }

        /** True when the swatch is effectively grey; HSL saturation of near-black is noise. */
        internal fun isAchromaticCoverColor(color: Int): Boolean {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            return hsl[1] * hslLightnessSpan(hsl[2]) < ACHROMATIC_CHROMA_THRESHOLD
        }

        /**
         * Pulls a swatch into its role's band.
         *
         * Two ideas carry the whole thing: a swatch whose hue is far from the anchor's is blended
         * toward the anchor so fields do not fight, and chroma is preserved across the lightness
         * lift (HSL saturation alone would read as more colourful than the source).
         */
        internal fun softenPaletteColor(
            rawColor: Int,
            anchorColor: Int,
            isDark: Boolean,
            role: DynamicBackgroundColorRole,
        ): Int {
            val rawHsl = FloatArray(3)
            val anchorHsl = FloatArray(3)
            ColorUtils.colorToHSL(rawColor, rawHsl)
            ColorUtils.colorToHSL(anchorColor, anchorHsl)

            val hueConflict = hueDistanceDegrees(rawHsl[0], anchorHsl[0]) >
                STRONG_HUE_CONFLICT_DEGREES && rawHsl[1] > 0.28f && anchorHsl[1] > 0.20f
            val anchorBlend = when (role) {
                DynamicBackgroundColorRole.Base -> 0.08f
                DynamicBackgroundColorRole.Accent -> if (hueConflict) 0.18f else 0.04f
                DynamicBackgroundColorRole.Light -> if (hueConflict) 0.24f else 0.08f
                DynamicBackgroundColorRole.Dark -> if (hueConflict) 0.42f else 0.22f
                DynamicBackgroundColorRole.Bridge -> 0.20f
            }
            val blendedColor = ColorUtils.blendARGB(rawColor, anchorColor, anchorBlend)
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(blendedColor, hsl)
            // HSL saturation of near-black swatches is noise; carry real chroma across the
            // lightness lift.
            val sourceChroma = hsl[1] * hslLightnessSpan(hsl[2])

            val saturationScale = when (role) {
                DynamicBackgroundColorRole.Base -> 0.94f
                DynamicBackgroundColorRole.Accent -> 1.14f
                DynamicBackgroundColorRole.Light -> 1.02f
                DynamicBackgroundColorRole.Dark -> 0.76f
                DynamicBackgroundColorRole.Bridge -> 0.96f
            }
            val saturationMax = if (role == DynamicBackgroundColorRole.Accent) {
                if (isDark) 0.70f else 0.64f
            } else if (isDark) {
                0.64f
            } else {
                0.56f
            }
            val saturationFloor = when {
                hsl[1] < 0.05f || isAchromaticCoverColor(anchorColor) -> 0f
                role == DynamicBackgroundColorRole.Dark -> 0.10f
                role == DynamicBackgroundColorRole.Accent -> 0.30f
                else -> 0.14f
            }

            val targetLightness = when (role) {
                DynamicBackgroundColorRole.Base -> if (isDark) 0.34f else 0.58f
                DynamicBackgroundColorRole.Accent -> if (isDark) 0.48f else 0.66f
                DynamicBackgroundColorRole.Light -> if (isDark) 0.62f else 0.82f
                DynamicBackgroundColorRole.Dark -> if (isDark) 0.18f else 0.34f
                DynamicBackgroundColorRole.Bridge -> if (isDark) 0.46f else 0.66f
            }
            val lightnessBlend = when (role) {
                DynamicBackgroundColorRole.Base -> 0.18f
                DynamicBackgroundColorRole.Accent -> 0.18f
                DynamicBackgroundColorRole.Light -> 0.24f
                DynamicBackgroundColorRole.Dark -> 0.34f
                DynamicBackgroundColorRole.Bridge -> 0.20f
            }
            val minLightness = if (isDark) 0.12f else 0.28f
            val maxLightness = if (isDark) 0.66f else 0.86f
            hsl[2] = lerpFloat(hsl[2], targetLightness, lightnessBlend)
                .coerceIn(minLightness, maxLightness)
            val chromaPreservingSaturation = sourceChroma / hslLightnessSpan(hsl[2]).coerceAtLeast(0.05f)
            hsl[1] = (chromaPreservingSaturation * saturationScale).coerceIn(saturationFloor, saturationMax)

            return ColorUtils.HSLToColor(hsl)
        }

        /** NeriPlayer's score for "how much does this swatch read as a colour". */
        internal fun colorfulnessScore(color: Int): Float {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            return hsl[1] * (0.45f + abs(hsl[2] - 0.5f).coerceAtMost(0.5f))
        }

        /** Lifts a dull accent to a usable saturation while keeping a hue, if it has one. */
        internal fun ensureAccentColor(color: Int, fallback: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[1] >= 0.24f) {
                return color
            }
            val fallbackHsl = FloatArray(3)
            ColorUtils.colorToHSL(fallback, fallbackHsl)
            val resolvedHue = when {
                hsl[1] > 0.04f -> hsl[0]
                fallbackHsl[1] > 0.04f -> fallbackHsl[0]
                else -> return color
            }
            hsl[0] = resolvedHue
            hsl[1] = 0.48f
            hsl[2] = hsl[2].coerceIn(0.40f, 0.68f)
            return ColorUtils.HSLToColor(hsl)
        }

        /** Caps the lightness at [targetLightness] without touching hue or saturation. */
        internal fun darkenColor(color: Int, targetLightness: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[2] = minOf(hsl[2], targetLightness)
            return ColorUtils.HSLToColor(hsl)
        }

        /** Rec.709 luminance of an ARGB colour, 0..1. Pure; the tone offsets are derived from it. */
        internal fun colorLuma(color: Int): Float {
            val r = ((color ushr 16) and 0xFF) / 255f
            val g = ((color ushr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        /** How much hue separation there is between two hues, in degrees. */
        internal fun hueDistanceDegrees(first: Float, second: Float): Float {
            val diff = abs(first - second) % 360f
            return minOf(diff, 360f - diff)
        }

        /** Chroma a given HSL lightness can carry (0 at black/white, 1 at mid grey). */
        internal fun hslLightnessSpan(lightness: Float): Float = 1f - abs(2f * lightness - 1f)

        private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
            start + (stop - start) * fraction.coerceIn(0f, 1f)
    }
}
