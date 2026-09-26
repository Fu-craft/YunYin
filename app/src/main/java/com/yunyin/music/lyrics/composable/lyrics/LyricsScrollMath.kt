package com.yunyin.music.lyrics.composable.lyrics

/**
 * The two pure helpers the lyrics view needs.
 *
 * (This file previously also held the scroll-offset maths. That has been removed: the focused
 * line is now positioned by `animateScrollToItem` plus symmetric content padding, so no offset
 * arithmetic is involved.)
 */
internal object LyricsScrollMath {

    /**
     * Index of the line that should be focused at [timeMs].
     *
     * Picks the **most recently started** line. Preferring the first line whose `[start, end)`
     * range contains the time lags whenever lines overlap — which they do, because a line's end
     * is extended to cover its accompaniment vocals, so `end[i]` can exceed `start[i + 1]`. A
     * range test would then keep showing the older line while the next has already begun.
     *
     * Selecting the latest start also gives the right behaviour in a gap: the previous line
     * stays until the next one actually begins.
     */
    fun anchorIndex(timeMs: Int, starts: IntArray, ends: IntArray): Int {
        if (starts.isEmpty()) return 0
        @Suppress("UNUSED_EXPRESSION")
        ends // Kept for call-site compatibility; selection does not depend on it.

        var lastStarted = -1
        for (i in starts.indices) {
            if (starts[i] <= timeMs) lastStarted = i else break
        }
        // Before the first line, show it.
        return if (lastStarted >= 0) lastStarted else 0
    }

    /**
     * Blur weight, in steps away from the focused range, clamped to [MAX_BLUR_STEPS].
     *
     * The clamp matters for performance, not looks. Radius is `weight * blurDelta`, and the raw
     * step count is unbounded, so a line 20 rows out asked for a 60px blur — recomputed for every
     * visible line on every frame, each inside its own offscreen layer. A line several steps out
     * is already unreadable, so stopping there costs nothing visually and bounds the per-frame
     * work. (This is the cap NeriPlayer also applies via `coerceIn(0f, 4f)` on its blur delta.)
     */
    fun blurWeight(index: Int, firstFocused: Int, lastFocused: Int): Int =
        maxOf(0, firstFocused - index, index - lastFocused).coerceAtMost(MAX_BLUR_STEPS)

    /**
     * Largest blur weight applied.
     *
     * Three steps is well past "illegible" at the 3px-per-step strength the view uses, so this is
     * a ceiling on cost with no visible effect.
     */
    const val MAX_BLUR_STEPS = 3
}
