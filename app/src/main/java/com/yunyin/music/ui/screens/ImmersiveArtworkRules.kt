// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/** What a tap on the in-layout artwork means. */
enum class ArtworkTapAction { Immersive, Lyrics }

/** Pure decisions for the tap-to-fill artwork interaction. */
object ImmersiveArtworkRules {
    fun tapAction(immersiveCoverEnabled: Boolean): ArtworkTapAction =
        if (immersiveCoverEnabled) ArtworkTapAction.Immersive else ArtworkTapAction.Lyrics

    fun shouldExitOnBack(immersive: Boolean): Boolean = immersive

    fun shouldExitOnLyrics(showLyrics: Boolean): Boolean = showLyrics

    fun shouldExitOnSwipe(distancePx: Float, elapsedMs: Long, thresholdPx: Float = 64f): Boolean {
        if (distancePx > thresholdPx) return true
        return elapsedMs > 0L && distancePx / elapsedMs > 0.11f
    }
}
