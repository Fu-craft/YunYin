// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import com.yunyin.music.ui.PlayerStyle

/** Pure geometry, colour and gating rules for the full-screen-cover player style. */
object FullScreenCoverRules {

    const val FEATHER_TOP_DP = 56f
    const val FEATHER_BOTTOM_DP = 72f
    const val TOP_LIGHTEN = 0.08f
    const val BOTTOM_DARKEN = 0.12f
    const val GRADIENT_MIDDLE = 0.55f
    const val FALLBACK_COLOR = 0xFF18181C.toInt()

    fun usesOwnBackdrop(style: PlayerStyle, isLandscape: Boolean): Boolean =
        style == PlayerStyle.Immersive && !isLandscape

    fun backgroundStyleComposed(style: PlayerStyle, isLandscape: Boolean): Boolean =
        !usesOwnBackdrop(style, isLandscape)

    /** The backdrop is one static brush; it never enters the frame-pump or shader path. */
    fun backdropIsStatic(style: PlayerStyle): Boolean = style == PlayerStyle.Immersive

    /** An immersive cover never shrinks on pause; the other styles keep their tuned value. */
    fun pausedScale(style: PlayerStyle, configuredScale: Float): Float =
        if (style == PlayerStyle.Immersive) 1f else configuredScale

    fun coverHeightPx(viewportWidthPx: Int): Int = viewportWidthPx.coerceAtLeast(1)

    fun extensionHeightPx(viewportHeightPx: Int, viewportWidthPx: Int): Int =
        (viewportHeightPx - coverHeightPx(viewportWidthPx)).coerceAtLeast(0)

    /** Top lighter → dominant → bottom darker, all at full alpha. */
    fun fieldColors(dominant: Int): IntArray = intArrayOf(
        mix(dominant, 0xFFFFFF, TOP_LIGHTEN),
        dominant or 0xFF000000.toInt(),
        mix(dominant, 0x000000, BOTTOM_DARKEN),
    )

    fun luminance(color: Int): Float {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    fun featherTopPx(viewportHeightPx: Int, density: Float): Float =
        (FEATHER_TOP_DP * density).coerceAtMost(viewportHeightPx.toFloat())

    fun featherBottomPx(viewportHeightPx: Int, density: Float): Float =
        (FEATHER_BOTTOM_DP * density).coerceAtMost(viewportHeightPx.toFloat())

    fun isCenterOpaque(yPx: Float, viewportHeightPx: Int, density: Float): Boolean {
        val top = featherTopPx(viewportHeightPx, density)
        val bottom = viewportHeightPx - featherBottomPx(viewportHeightPx, density)
        return yPx in top..bottom
    }

    private fun mix(color: Int, target: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val from = (color ushr shift) and 0xFF
            val to = (target ushr shift) and 0xFF
            return (from + (to - from) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
