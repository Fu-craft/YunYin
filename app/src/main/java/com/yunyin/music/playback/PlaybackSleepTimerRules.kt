// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

/**
 * Pure deadline math for the sleep timer.
 *
 * Kept out of [PlayerController] so "15 minutes means 15 minutes from now", "remaining rounds up" and "fire at or
 * after the deadline" are testable without a player. The controller owns the coroutine; this owns the arithmetic.
 */
object PlaybackSleepTimerRules {

    /** Sentinel for "关闭": no timer. */
    const val OFF = 0

    /** Sentinel for "播完当前歌": pause when the current item ends. */
    const val END_OF_TRACK = -1

    val OPTION_MINUTES: List<Int> = listOf(15, 30, 45, 60)

    fun deadline(nowMs: Long, minutes: Int): Long = nowMs + minutes.coerceAtLeast(1) * 60_000L

    /** Whole minutes still left, rounded up; null when no timer is armed. */
    fun remainingMinutes(nowMs: Long, endMs: Long?): Int? =
        endMs?.let { end ->
            val remaining = end - nowMs
            if (remaining <= 0L) 0 else ((remaining + 59_999L) / 60_000L).toInt()
        }

    fun shouldFire(nowMs: Long, endMs: Long?): Boolean = endMs != null && nowMs >= endMs
}
