package com.yunyin.music.playback

/**
 * When a play should be reported to the NetEase account (`/scrobble`).
 *
 * ## Why accumulated playback, not the reported position
 *
 * The conventional "played > 50 % or > 4 minutes" rule is usually applied to the *maximum position*, but that
 * counts a seek straight to the end as a full play. The user asked for a scrobble at transitions, not for a
 * position hook, so this rule accumulates the time the transport really advanced (only while playing, only for
 * small forward deltas) and fires when that reaches the threshold:
 *
 * `threshold = min(duration / 2, 4 minutes)`; an unknown duration falls back to 4 minutes.
 *
 * A pause, a backward seek or a large jump contributes nothing, and seeking to the end cannot satisfy the
 * threshold by itself. Reporting happens at the transition/pause/release, never mid-play, and at most once per
 * track (`Progress.reported`).
 *
 * Pure and unit-tested; the player passes it the polled position and its own play state.
 */
object ScrobbleRules {

    /** The four-minute conventional cap, also the fallback when the duration is unknown. */
    const val MAX_THRESHOLD_MS = 240_000L

    /** A forward delta larger than this is a seek, not listening, so it does not count. */
    const val MAX_TICK_DELTA_MS = 2_500L

    data class Progress(
        val playedMs: Long = 0L,
        val lastPositionMs: Long = 0L,
        val reported: Boolean = false,
    )

    fun thresholdMs(durationMs: Long): Long =
        if (durationMs > 0L) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS

    /** Feeds one authoritative position poll; [isPlaying] is the transport's actual state, not `playWhenReady`. */
    fun onTick(progress: Progress, positionMs: Long, isPlaying: Boolean): Progress {
        val position = positionMs.coerceAtLeast(0L)
        if (!isPlaying) return progress.copy(lastPositionMs = position)
        val delta = position - progress.lastPositionMs
        val played = if (delta in 1..MAX_TICK_DELTA_MS) progress.playedMs + delta else progress.playedMs
        return progress.copy(playedMs = played, lastPositionMs = position)
    }

    fun shouldReport(progress: Progress, durationMs: Long): Boolean =
        !progress.reported && progress.playedMs >= thresholdMs(durationMs)

    /** The position to send as `/scrobble`'s `time`, clamped into the track and expressed in seconds. */
    fun reportSeconds(progress: Progress, durationMs: Long): Int {
        val played = if (durationMs > 0L) minOf(progress.playedMs, durationMs) else progress.playedMs
        return (played / 1000L).toInt().coerceAtLeast(0)
    }
}
