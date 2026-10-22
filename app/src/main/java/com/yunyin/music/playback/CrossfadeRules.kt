package com.yunyin.music.playback

import androidx.media3.common.Player
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure rules for the dual-player overlapping crossfade.
 *
 * Two ExoPlayers play concurrently for the fade window. This object decides **whether** that heavy path may run and
 * supplies the equal-power gains and time mapping. Every uncertainty returns false, so the caller falls back to
 * normal single-player playback instead of risking a gap or two full-volume tracks.
 */
object CrossfadeRules {

    val DURATIONS_SECONDS = listOf(3, 6, 9, 12)
    const val DEFAULT_SECONDS = 6

    /** Legacy volume-fade length, used only if the overlap path is unavailable. */
    const val FADE_MS = 1_500L

    /** Deadline for the secondary to become READY before the overlap is abandoned. */
    const val PRELOAD_DEADLINE_MS = 1_500L

    /** Start preloading this long before the boundary (fade + margin). */
    const val PRELOAD_MARGIN_MS = 1_000L

    fun durationMs(seconds: Int): Long = seconds.coerceIn(0, 12) * 1_000L

    fun hasNextItem(index: Int, itemCount: Int, repeatMode: Int): Boolean {
        if (itemCount <= 1) return false
        if (index < itemCount - 1) return true
        return repeatMode == Player.REPEAT_MODE_ALL
    }

    fun nextIndex(index: Int, itemCount: Int, repeatMode: Int): Int? {
        if (!hasNextItem(index, itemCount, repeatMode)) return null
        return if (index < itemCount - 1) index + 1 else 0
    }

    fun shouldStartPreload(
        enabled: Boolean,
        playing: Boolean,
        secondaryAvailable: Boolean,
        stateIsIdle: Boolean,
        remainingMs: Long,
        fadeMs: Long,
        nextIndex: Int?,
        repeatMode: Int,
    ): Boolean {
        if (!enabled || !playing || secondaryAvailable || !stateIsIdle) return false
        if (nextIndex == null || nextIndex < 0 || repeatMode == Player.REPEAT_MODE_ONE) return false
        if (remainingMs <= 0L) return false
        return remainingMs <= fadeMs + PRELOAD_MARGIN_MS
    }

    fun shouldStartFade(
        preloadReady: Boolean,
        fadeStarted: Boolean,
        remainingMs: Long,
        fadeMs: Long,
        playing: Boolean,
    ): Boolean = preloadReady && !fadeStarted && playing && fadeMs > 0L && remainingMs <= fadeMs + 250L

    /** Equal-power gains: out = cos(t·π/2), in = sin(t·π/2); squares sum to 1, both monotone. */
    fun gains(progress: Float): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f)
        val angle = t * (Math.PI.toFloat() / 2f)
        return cos(angle) to sin(angle)
    }

    fun isFinished(progress: Float): Boolean = progress >= 1f

    fun shouldAbort(
        playing: Boolean,
        repeatMode: Int,
        previousPositionMs: Long,
        positionMs: Long,
    ): Boolean =
        !playing || repeatMode == Player.REPEAT_MODE_ONE || positionMs < previousPositionMs - 2_000L

    fun preloadDeadlineExpired(deadlinePassed: Boolean, ready: Boolean): Boolean = deadlinePassed && !ready
}
