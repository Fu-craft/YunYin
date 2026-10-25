// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
// Backdrop composition ideas from NeriPlayer (GPL-3.0), © NeriPlayer contributors.

package com.yunyin.music.ui.background

/** Pure gating and constants for the opt-in immersive cover backdrop. */
object ImmersiveCoverRules {

    const val RIPPLE_AMPLITUDE = 0.006f
    const val RIPPLE_VERTICAL_AMPLITUDE = 0.004f
    const val RIPPLE_SPEED = 0.55f
    const val RIPPLE_CROSS_SPEED = 0.42f
    const val FRAME_RATE = 60
    const val FRAME_INTERVAL_NS = 1_000_000_000L / FRAME_RATE

    const val DESATURATION = 0.35f
    const val BRIGHTNESS = 0.58f
    const val VIGNETTE = 0.28f
    const val GRADIENT_START = 0.44f
    const val GRADIENT_END = 0.74f

    fun shouldRender(enabled: Boolean, isLandscape: Boolean, hasCover: Boolean): Boolean =
        enabled && !isLandscape && hasCover

    fun shouldAnimate(
        enabled: Boolean,
        dynamicBackground: Boolean,
        animatorsEnabled: Boolean,
        transitioning: Boolean,
    ): Boolean = enabled && dynamicBackground && animatorsEnabled && !transitioning
}
