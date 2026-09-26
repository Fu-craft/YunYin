package com.yunyin.music.ui.background

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

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
    /** Mean lightness of the base colour, 0..1 — used for UI contrast decisions. */
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

        /** Used before artwork loads, and if extraction fails. */
        fun fallback(isDark: Boolean = false): DynamicBackgroundPalette = fromColors(
            intArrayOf(FALLBACK, FALLBACK, FALLBACK, FALLBACK, FALLBACK),
            isDark,
        )

        /** Extracts the palette from artwork. Safe to call off the main thread. */
        fun from(bitmap: Bitmap, isDark: Boolean): DynamicBackgroundPalette {
            val palette = runCatching {
                Palette.from(bitmap)
                    // Keep the artwork's real colours; the default filters discard the
                    // swatches that actually characterise the cover.
                    .clearFilters()
                    .maximumColorCount(16)
                    .generate()
            }.getOrNull() ?: return fallback(isDark)

            val base = palette.dominantSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: FALLBACK
            val accentSource = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: base
            val lightSource = palette.lightVibrantSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb
                ?: lighten(base, 0.28f)
            val darkSource = palette.darkVibrantSwatch?.rgb
                ?: palette.darkMutedSwatch?.rgb
                ?: darken(base, 0.30f)

            return fromColors(
                intArrayOf(
                    soften(base),
                    soften(accentSource),
                    soften(lightSource),
                    soften(darkSource),
                    // Bridge tone: keeps adjacent fields from fighting.
                    soften(blend(base, accentSource, 0.5f)),
                ),
                isDark,
            )
        }

        fun fromColors(colors: IntArray, isDark: Boolean): DynamicBackgroundPalette {
            val baseLuma = luma(colors.first())
            return DynamicBackgroundPalette(
                colors = colors,
                saturateOffset = if (isDark) 0.24f else 0.16f,
                lightOffset = if (isDark) {
                    (-0.06f + 0.12f * (baseLuma - 0.5f)).coerceIn(-0.12f, 0.12f)
                } else {
                    (0.08f + 0.10f * (0.5f - baseLuma)).coerceIn(-0.12f, 0.12f)
                },
                baseLuminance = baseLuma,
                accent = colors.getOrElse(1) { colors.first() },
            )
        }

        // ------------------------------------------------------------ colour helpers

        /** Mean perceived lightness, 0..1. */
        private fun luma(color: Int): Float {
            val r = (color ushr 16 and 0xFF) / 255f
            val g = (color ushr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        /**
         * Pulls a colour toward a comfortable band.
         *
         * The shader already boosts saturation and lightness, so a fully saturated source
         * would clip; this trims extremes before it becomes an overlay.
         */
        private fun soften(color: Int): Int {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[1] = (hsv[1] * 0.82f).coerceIn(0.10f, 0.78f)
            hsv[2] = hsv[2].coerceIn(0.22f, 0.92f)
            return android.graphics.Color.HSVToColor(hsv)
        }

        private fun blend(a: Int, b: Int, ratio: Float): Int {
            val inv = 1f - ratio
            return android.graphics.Color.rgb(
                ((a ushr 16 and 0xFF) * inv + (b ushr 16 and 0xFF) * ratio).toInt().coerceIn(0, 255),
                ((a ushr 8 and 0xFF) * inv + (b ushr 8 and 0xFF) * ratio).toInt().coerceIn(0, 255),
                ((a and 0xFF) * inv + (b and 0xFF) * ratio).toInt().coerceIn(0, 255),
            )
        }

        private fun lighten(color: Int, amount: Float): Int {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[2] = (hsv[2] + amount).coerceAtMost(1f)
            return android.graphics.Color.HSVToColor(hsv)
        }

        private fun darken(color: Int, amount: Float): Int {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[2] = (hsv[2] - amount).coerceAtLeast(0f)
            return android.graphics.Color.HSVToColor(hsv)
        }
    }
}
