// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import kotlin.math.roundToInt

/** Pure formatting and media-volume maths for the Apple Music-style player. */
object PlayerChromeRules {

    fun formatElapsed(positionMs: Long): String {
        val total = (positionMs.coerceAtLeast(0L) / 1000L)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun formatRemaining(positionMs: Long, durationMs: Long): String {
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        if (remaining <= 0L) return "0:00"
        val total = remaining / 1000L
        return "-${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun volumeFraction(volume: Int, maxVolume: Int): Float =
        if (maxVolume <= 0) 0f else (volume.toFloat() / maxVolume).coerceIn(0f, 1f)

    fun volumeFor(fraction: Float, maxVolume: Int): Int {
        if (maxVolume <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
    }

    /** A fixed stream or a zero maximum means the row would be a dead control. */
    fun shouldShowVolume(maxVolume: Int, volumeFixed: Boolean): Boolean =
        maxVolume > 0 && !volumeFixed
}
