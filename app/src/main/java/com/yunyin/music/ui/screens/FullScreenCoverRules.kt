// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import com.yunyin.music.ui.PlayerStyle

/** Pure geometry/gating for the full-screen-cover player style. */
object FullScreenCoverRules {

    const val BLUR_SOURCE_SIZE = 96
    const val BLUR_PASSES = 2
    const val BLUR_RADIUS = 4
    const val DARKEN = 0.35f

    fun usesOwnBackdrop(style: PlayerStyle, isLandscape: Boolean): Boolean =
        style == PlayerStyle.Immersive && !isLandscape

    fun coverHeightPx(viewportWidthPx: Int): Int = viewportWidthPx.coerceAtLeast(1)

    /** The blurred extension fills the viewport below the square cover. */
    fun extensionHeightPx(viewportHeightPx: Int, viewportWidthPx: Int): Int =
        (viewportHeightPx - coverHeightPx(viewportWidthPx)).coerceAtLeast(0)

    fun blurSourceSize(coverWidthPx: Int): Int =
        (coverWidthPx / 8).coerceIn(24, BLUR_SOURCE_SIZE)

    fun backgroundStyleComposed(style: PlayerStyle, isLandscape: Boolean): Boolean =
        !usesOwnBackdrop(style, isLandscape)
}
