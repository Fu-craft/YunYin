package com.yunyin.music.lyrics.composable.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the smoothing of the drawn lyric clock.
 *
 * The player publishes its position only a few times a second, so the syllable fill used to
 * advance in visible steps. The smoothing must remove that without ever delaying a line change,
 * and must snap rather than chase after a seek.
 */
class LyricTimeSmoothingTest {

    @Test
    fun `a small forward gap is eased, not jumped`() {
        // 100ms ahead: one step should move partway, never all the way.
        val next = LyricTimeSmoothing.next(reported = 1_100, current = 1_000)
        assertTrue("should advance", next > 1_000)
        assertTrue("should not jump the whole gap", next < 1_100)
    }

    @Test
    fun `backwards time snaps immediately`() {
        // A seek or a loop: easing backwards would look like the fill rewinding.
        assertEquals(500, LyricTimeSmoothing.next(reported = 500, current = 4_000))
    }

    @Test
    fun `a large forward jump snaps immediately`() {
        val far = LyricTimeSmoothing.next(
            reported = 60_000,
            current = 1_000,
        )
        assertEquals(60_000, far)
    }

    @Test
    fun `snap threshold boundary is inclusive of easing`() {
        val atThreshold = LyricTimeSmoothing.next(
            reported = LyricTimeSmoothing.SNAP_THRESHOLD_MS,
            current = 0,
        )
        // Exactly at the threshold still eases (it is a small gap), so it must not be the full jump.
        assertTrue("threshold gap should ease", atThreshold < LyricTimeSmoothing.SNAP_THRESHOLD_MS)

        val beyond = LyricTimeSmoothing.next(
            reported = LyricTimeSmoothing.SNAP_THRESHOLD_MS + 1,
            current = 0,
        )
        assertEquals(LyricTimeSmoothing.SNAP_THRESHOLD_MS + 1, beyond)
    }

    @Test
    fun `it converges without overshooting`() {
        var current = 0
        val reported = 300
        var iterations = 0
        while (current < reported && iterations < 1_000) {
            val next = LyricTimeSmoothing.next(reported = reported, current = current)
            assertTrue("must not overshoot", next <= reported)
            assertTrue("must make progress", next >= current)
            current = next
            iterations++
        }
        assertEquals(reported, current)
        // Should settle in a few frames, not crawl.
        assertTrue("settled in $iterations steps", iterations < 20)
    }

    @Test
    fun `no gap is a no-op`() {
        assertEquals(1_234, LyricTimeSmoothing.next(reported = 1_234, current = 1_234))
    }
}
