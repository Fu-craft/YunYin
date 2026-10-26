// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

/** Pure lifecycle/gating rules for the floating desktop-lyrics overlay. */
object DesktopLyricsRules {
    const val UPDATE_INTERVAL_MS = 100L
    const val STOP_AFTER_MS = 30_000L

    fun shouldShow(enabled: Boolean, permission: Boolean, hasTrack: Boolean): Boolean =
        enabled && permission && hasTrack

    fun shouldStop(playing: Boolean, elapsedSincePlayingMs: Long): Boolean =
        !playing && elapsedSincePlayingMs > STOP_AFTER_MS
}
