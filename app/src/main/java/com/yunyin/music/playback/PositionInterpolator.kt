package com.yunyin.music.playback

/**
 * Interpolates the playback position between authoritative player polls.
 *
 * The lyrics are timed against this value on every frame, so it must never run ahead of the
 * audio. ExoPlayer's reported position only advances while audio is actually being rendered:
 * during buffering (and while paused) it is frozen. Extrapolating in those states pushed the
 * lyric clock ahead of the sound, which made lines begin before the lead-in had finished and
 * before the previous line's audio had ended.
 *
 * Extrapolation is therefore:
 *  - disabled unless audio is genuinely playing (not buffering), and
 *  - bounded, so a delayed poll cannot let the clock drift.
 *
 * Extracted from the controller so the timing rules can be tested directly.
 */
internal class PositionInterpolator {

    var anchorPositionMs: Long = 0L
        private set
    var anchorRealtimeMs: Long = 0L
        private set
    var advancing: Boolean = false
        private set

    /** Nearest duration, used to clamp so the position can never exceed the track. */
    var durationMs: Long = 0L

    /**
     * Re-anchors from an authoritative player reading.
     *
     * @param advancing whether audio is actually playing out (not buffering, not paused).
     */
    fun anchor(positionMs: Long, realtimeMs: Long, advancing: Boolean) {
        anchorPositionMs = positionMs.coerceAtLeast(0L)
        anchorRealtimeMs = realtimeMs
        this.advancing = advancing
    }

    /** Jumps the position immediately (e.g. after a seek). */
    fun seekTo(positionMs: Long, realtimeMs: Long) {
        anchorPositionMs = positionMs.coerceAtLeast(0L)
        anchorRealtimeMs = realtimeMs
    }

    /**
     * The position to display now.
     *
     * When not advancing, the last anchored position is returned unchanged — the audio has
     * not moved, so neither may the lyrics.
     */
    fun positionAt(realtimeMs: Long, maxExtrapolationMs: Long): Long {
        if (!advancing) return anchorPositionMs
        val elapsed = (realtimeMs - anchorRealtimeMs).coerceIn(0L, maxExtrapolationMs)
        val projected = anchorPositionMs + elapsed
        return if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected
    }

    companion object {
        /** The player publishes every 250ms; a bound this size keeps motion smooth and safe. */
        const val MAX_EXTRAPOLATION_MS = 400L
    }
}
