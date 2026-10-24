// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/**
 * Geometry for the progress bar's filled capsule.
 *
 * At playback start the filled width is shorter than the track is thick, so `drawRoundRect` clamps the
 * corner radius and the leading end renders as a tiny **square** sliver until the fill grows past one
 * track height. Drawing the fill at least one thickness wide keeps the corner radius valid (width ≥
 * 2·radius) so the end is round at every progress; zero progress still draws nothing.
 */
internal object ProgressCapsuleRules {
    fun drawnFillWidth(fillPx: Float, thicknessPx: Float, fullWidthPx: Float): Float =
        if (fillPx <= 0f) {
            0f
        } else {
            fillPx.coerceAtLeast(thicknessPx).coerceAtMost(fullWidthPx)
        }
}
