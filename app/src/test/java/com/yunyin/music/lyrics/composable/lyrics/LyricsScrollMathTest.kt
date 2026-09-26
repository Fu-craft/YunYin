package com.yunyin.music.lyrics.composable.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the lyrics blur weighting.
 *
 * The blur applied to a line is `blurWeight * blurDelta`, so an unbounded weight meant a line far
 * down the list asked for a very large blur — recomputed for every visible line on every frame,
 * each in its own offscreen layer. That was per-frame cost with no visual benefit, since a line
 * several steps out is already unreadable. These tests pin the clamp so it cannot be dropped again.
 */
class LyricsScrollMathTest {

    @Test
    fun `focused lines have zero weight`() {
        assertEquals(0, LyricsScrollMath.blurWeight(index = 5, firstFocused = 5, lastFocused = 5))
        // A focused *range* (duet) is also zero for both members.
        assertEquals(0, LyricsScrollMath.blurWeight(index = 6, firstFocused = 5, lastFocused = 7))
    }

    @Test
    fun `weight grows with distance from the focused range`() {
        assertEquals(1, LyricsScrollMath.blurWeight(index = 6, firstFocused = 5, lastFocused = 5))
        assertEquals(2, LyricsScrollMath.blurWeight(index = 3, firstFocused = 5, lastFocused = 5))
        assertEquals(3, LyricsScrollMath.blurWeight(index = 2, firstFocused = 5, lastFocused = 5))
    }

    @Test
    fun `weight is clamped so the blur radius stays bounded`() {
        assertEquals(
            LyricsScrollMath.MAX_BLUR_STEPS,
            LyricsScrollMath.blurWeight(index = 500, firstFocused = 5, lastFocused = 5),
        )
        assertEquals(
            LyricsScrollMath.MAX_BLUR_STEPS,
            LyricsScrollMath.blurWeight(index = 0, firstFocused = 400, lastFocused = 400),
        )

        // The property that matters: no index can produce an unbounded radius.
        for (index in 0..600) {
            val w = LyricsScrollMath.blurWeight(index, firstFocused = 300, lastFocused = 300)
            assertTrue("weight $w out of range at index $index", w in 0..LyricsScrollMath.MAX_BLUR_STEPS)
        }
    }

    @Test
    fun `clamp does not collapse distinct distances that matter`() {
        // One and two steps out must stay distinguishable — the falloff across nearby lines is
        // what makes the focused line stand out, so the clamp must sit beyond them.
        val focused = 3
        assertEquals(1, LyricsScrollMath.blurWeight(index = focused - 1, firstFocused = focused, lastFocused = focused))
        assertEquals(2, LyricsScrollMath.blurWeight(index = focused - 2, firstFocused = focused, lastFocused = focused))
        assertTrue("clamp must be above the near steps", LyricsScrollMath.MAX_BLUR_STEPS >= 2)
    }

    @Test
    fun `anchor holds the most recently started line during a gap`() {
        // Lines start at 0/10s/20s; at 15s (a gap after line 2) the anchor is line 2, not line 3.
        val starts = intArrayOf(0, 10_000, 20_000)
        val ends = intArrayOf(9_000, 19_000, 29_000)
        assertEquals(1, LyricsScrollMath.anchorIndex(15_000, starts, ends))
        assertEquals(2, LyricsScrollMath.anchorIndex(25_000, starts, ends))
        // Before the first line, show the first line.
        assertEquals(0, LyricsScrollMath.anchorIndex(-500, starts, ends))
    }
}
