package com.yunyin.music.lyrics.composable.lyrics

/**
 * Smooths the playback clock used for drawing the syllable fill.
 *
 * The player publishes its position only a few times a second, so reading it directly makes the
 * karaoke fill advance in visible steps. This eases the *displayed* time toward the reported one.
 *
 * Two properties matter and are both deliberate:
 *
 * - **It never delays line changes.** Line selection keeps using the raw playback time (see the
 *   `renderCurrentPosition` contract), so a smoothed value can never hold a line back.
 * - **It snaps instead of chasing.** After a seek the gap is large; easing across it would look
 *   like the fill rushing to catch up, so a gap beyond [SNAP_THRESHOLD_MS] is applied at once.
 *
 * Ported from the approach NeriPlayer uses (`shouldSnapLyricTimeSmoothing` plus a short tween).
 */
internal object LyricTimeSmoothing {

    /** Maximum gap still eased; anything larger is applied immediately. */
    const val SNAP_THRESHOLD_MS = 400

    /** Fraction of the remaining gap taken per update. Sized for a ~100ms settle. */
    const val EASE_FACTOR = 0.35f

    /**
     * Next displayed time given the [current] displayed value and the [reported] playback time.
     *
     * Returning [reported] unchanged when the gap is within a millisecond keeps the value stable
     * so it does not invalidate drawing on every frame forever.
     */
    fun next(reported: Int, current: Int): Int {
        val delta = reported - current
        if (delta == 0) return current
        // Backwards (a seek or a loop) and large jumps must not be eased.
        if (delta < 0 || delta > SNAP_THRESHOLD_MS) return reported
        val step = (delta * EASE_FACTOR)
        val advance = if (step < 1f) 1 else step.toInt()
        return (current + advance).coerceAtMost(reported)
    }
}
