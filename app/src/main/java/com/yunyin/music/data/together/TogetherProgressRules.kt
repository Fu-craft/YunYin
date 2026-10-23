package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

/**
 * together.js reports `PROGRESS` only when `seekRevision` changes (a user seek). We have no revision, so the
 * equivalent intent is "position jumped away from where time alone would have put it".
 */
object TogetherProgressRules {
    const val TOLERANCE_MS = 2_000L
    const val SETTLE_MS = 2_000L

    /** Where playback should be now given the last known sample: time advances only while actually playing. */
    fun expectedPosition(
        positionMs: Long,
        sampledAtMs: Long,
        nowMs: Long,
        playing: Boolean,
        buffering: Boolean,
    ): Long {
        val elapsed = if (playing && !buffering) (nowMs - sampledAtMs).coerceAtLeast(0L) else 0L
        return positionMs + elapsed
    }

    /** A real seek: the actual position is far from the expected one. Natural advance never triggers. */
    fun isRealSeek(actualMs: Long, expectedMs: Long, toleranceMs: Long = TOLERANCE_MS): Boolean =
        kotlin.math.abs(actualMs - expectedMs) > toleranceMs

    /** Remote PROGRESS within tolerance carries no information; seeking for it would create a ping-pong. */
    fun shouldSeekRemote(remoteMs: Long, expectedMs: Long, toleranceMs: Long = TOLERANCE_MS): Boolean =
        isRealSeek(remoteMs, expectedMs, toleranceMs)

    fun inSettleWindow(nowMs: Long, settleUntilMs: Long): Boolean = nowMs < settleUntilMs
}
