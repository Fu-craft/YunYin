package com.yunyin.music.playback

/**
 * Interpolates the playback position between authoritative player polls.
 *
 * The lyrics are timed against this value on every frame, so it has to satisfy three things:
 *
 *  1. **Never run ahead of the audio.** ExoPlayer's reported position only advances while audio is
 *     actually being rendered: during buffering (and while paused) it is frozen. Extrapolating in
 *     those states pushed the lyric clock ahead of the sound, so lines began before the lead-in
 *     finished. Extrapolation is therefore disabled unless audio is genuinely playing, and bounded
 *     so a delayed poll cannot let it drift.
 *
 *  2. **Never step backwards.** This is the part that used to go wrong. The player is polled every
 *     250 ms while the clock extrapolates up to [MAX_EXTRAPOLATION_MS]; whenever that estimate
 *     overshot the true position, the next poll re-anchored the clock *lower* than the value that
 *     had already been drawn. Through the smoothing that feeds the karaoke fill, a negative step is
 *     applied instantly, so the highlight visibly retreated for a moment and then carried on.
 *     A continuous playback clock is monotonic, so this one is too: an overshoot is corrected by
 *     briefly advancing *slower* than real time (see [CONVERGE_MS]), never by moving back. The
 *     audio catches up to the display, rather than the display jumping back to the audio.
 *
 *  3. **Still follow real discontinuities.** A seek or a track change genuinely moves the position,
 *     often backwards. Those are far larger than any interpolation error, so a drop beyond
 *     [DISCONTINUITY_MS] is taken as authoritative and applied immediately. Explicit seeks also
 *     reset the clock outright via [seekTo].
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

    /** The value last handed out, which is what the clock must never step below. */
    private var displayedMs: Long = 0L

    /** Realtime of the previous [positionAt], for the convergence rate. */
    private var lastRealtimeMs: Long = 0L

    private var started: Boolean = false

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

    /**
     * Jumps the position immediately, in either direction.
     *
     * Used for a seek, which is the one case where moving backwards is correct — so it also resets
     * the monotonic guard, otherwise the clock would treat the seek as an error to be corrected.
     */
    fun seekTo(positionMs: Long, realtimeMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        anchorPositionMs = target
        anchorRealtimeMs = realtimeMs
        displayedMs = target
        lastRealtimeMs = realtimeMs
        started = true
    }

    /**
     * The position to display now.
     *
     * When not advancing, the last position is returned unchanged — the audio has not moved, so
     * neither may the lyrics.
     */
    fun positionAt(realtimeMs: Long, maxExtrapolationMs: Long): Long {
        val truth = if (advancing) {
            val elapsed = (realtimeMs - anchorRealtimeMs).coerceIn(0L, maxExtrapolationMs)
            val projected = anchorPositionMs + elapsed
            if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected
        } else {
            anchorPositionMs
        }

        if (!started) {
            started = true
            lastRealtimeMs = realtimeMs
            displayedMs = truth
            return displayedMs
        }

        val dt = (realtimeMs - lastRealtimeMs).coerceAtLeast(0L)
        lastRealtimeMs = realtimeMs

        // Normal case: moving forward (or holding still). Follow the estimate exactly.
        if (truth >= displayedMs) {
            displayedMs = truth
            return displayedMs
        }

        // The previous estimate overshot. A large drop is a real discontinuity, not an error.
        val lead = displayedMs - truth
        if (lead > DISCONTINUITY_MS) {
            displayedMs = truth
            return displayedMs
        }

        // Paused or buffering: hold entirely still rather than creeping forward.
        if (!advancing) return displayedMs

        // Otherwise shed the lead by advancing slower than real time, so the audio catches up to
        // the display. The rate is chosen to close the gap over roughly [CONVERGE_MS] regardless of
        // its size: a small correction barely slows the clock, a large one slows it more, and the
        // display never moves backwards.
        val rate = 1f - (lead.toFloat() / CONVERGE_MS).coerceIn(0f, MAX_SLOWDOWN)
        displayedMs += (dt * rate).toLong().coerceAtLeast(0L)
        return displayedMs
    }

    companion object {
        /** The player publishes every 250ms; a bound this size keeps motion smooth and safe. */
        const val MAX_EXTRAPOLATION_MS = 400L

        /**
         * A backward step larger than this is treated as a real jump (seek / track change / loop)
         * rather than as interpolation error, and is applied at once.
         *
         * Comfortably above [MAX_EXTRAPOLATION_MS], which bounds the error the clock can actually
         * produce, and comfortably below any deliberate seek in this app — the progress bar routes
         * through [seekTo], and a track change moves the position by whole minutes.
         */
        const val DISCONTINUITY_MS = 800L

        /** Target time over which an overshoot is absorbed, in milliseconds. */
        private const val CONVERGE_MS = 300f

        /** Floor on the advance rate while converging, so the clock never stalls mid-line. */
        private const val MAX_SLOWDOWN = 0.9f
    }
}
