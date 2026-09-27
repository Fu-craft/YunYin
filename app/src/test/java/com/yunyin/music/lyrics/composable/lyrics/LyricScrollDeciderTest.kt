package com.yunyin.music.lyrics.composable.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the lyric auto-scroll decision.
 *
 * Every one of these pins a behaviour that was wrong in the inline version this replaced, so the
 * tests exist to stop those specific regressions rather than to describe the happy path.
 */
class LyricScrollDeciderTest {

    private fun scrollOf(decision: LyricScrollDecider.Decision): LyricScrollDecider.Decision.Scroll {
        assertTrue("expected a Scroll, got $decision", decision is LyricScrollDecider.Decision.Scroll)
        return decision as LyricScrollDecider.Decision.Scroll
    }

    @Test
    fun `the first placement jumps instead of animating`() {
        // Starting a song must not animate from line 0 to line 40 — that scrolls the whole list past
        // every line above and reads as the lyrics rushing up from the bottom.
        val d = LyricScrollDecider()
        val first = scrollOf(d.decide(index = 40, dragging = false, firstVisibleIndex = 0))
        assertEquals(40, first.index)
        assertEquals("the first placement must not animate", false, first.animate)
    }

    @Test
    fun `later line changes animate`() {
        val d = LyricScrollDecider()
        scrollOf(d.decide(40, dragging = false, firstVisibleIndex = 0))
        // Settled on 40.
        assertEquals(LyricScrollDecider.Decision.Wait, d.decide(40, dragging = false, firstVisibleIndex = 40))
        val next = scrollOf(d.decide(41, dragging = false, firstVisibleIndex = 40))
        assertEquals(41, next.index)
        assertTrue("a line change should animate", next.animate)
    }

    @Test
    fun `an already-satisfied request is not repeated`() {
        // Re-issuing the same scroll every frame is what made the list fight itself.
        val d = LyricScrollDecider()
        scrollOf(d.decide(10, dragging = false, firstVisibleIndex = 0))
        repeat(5) {
            assertEquals(
                LyricScrollDecider.Decision.Wait,
                d.decide(10, dragging = false, firstVisibleIndex = 10),
            )
        }
    }

    @Test
    fun `a cancelled scroll is retried`() {
        // The list did not end up on the requested line (a fling or another scroll won), so the
        // request must be re-issued rather than considered done.
        val d = LyricScrollDecider()
        scrollOf(d.decide(10, dragging = false, firstVisibleIndex = 0))
        val retry = scrollOf(d.decide(10, dragging = false, firstVisibleIndex = 3))
        assertEquals(10, retry.index)
    }

    @Test
    fun `a drag holds the viewport until playback moves on`() {
        // The user scrolled away deliberately. The list must stay there — not snap back — for as long
        // as playback is still on the line it was on.
        val d = LyricScrollDecider()
        scrollOf(d.decide(10, dragging = false, firstVisibleIndex = 0))
        assertEquals(LyricScrollDecider.Decision.Wait, d.decide(10, dragging = false, firstVisibleIndex = 10))

        // Finger down and dragging.
        assertEquals(LyricScrollDecider.Decision.Wait, d.decide(10, dragging = true, firstVisibleIndex = 10))
        // Released, still on the same line: keep holding.
        repeat(3) {
            assertEquals(
                "the hold must survive the drag ending",
                LyricScrollDecider.Decision.Wait,
                d.decide(10, dragging = false, firstVisibleIndex = 25),
            )
        }
        // Playback moves to the next line: the hold releases and following resumes.
        val resumed = scrollOf(d.decide(11, dragging = false, firstVisibleIndex = 25))
        assertEquals(11, resumed.index)
        assertTrue(resumed.animate)
    }

    @Test
    fun `no target means no scrolling`() {
        // Used for the lead-in, where the breathing dots own the position.
        val d = LyricScrollDecider()
        assertEquals(LyricScrollDecider.Decision.Wait, d.decide(null, dragging = false, firstVisibleIndex = 0))
        // And it must not have consumed the "first placement" flag.
        val first = scrollOf(d.decide(5, dragging = false, firstVisibleIndex = 0))
        assertEquals(false, first.animate)
    }

    @Test
    fun `a drag before any placement still allows a jump afterwards`() {
        // Edge case: the user drags during the lead-in, before the list has ever been placed. The
        // first real placement must still be a jump, not an animation from wherever they left it.
        val d = LyricScrollDecider()
        d.decide(null, dragging = false, firstVisibleIndex = 0)
        assertEquals(LyricScrollDecider.Decision.Wait, d.decide(0, dragging = true, firstVisibleIndex = 0))
        val first = scrollOf(d.decide(3, dragging = false, firstVisibleIndex = 0))
        assertEquals(false, first.animate)
    }

    @Test
    fun `reset clears the hold and the placement flag`() {
        val d = LyricScrollDecider()
        scrollOf(d.decide(10, dragging = false, firstVisibleIndex = 0))
        d.decide(10, dragging = true, firstVisibleIndex = 10)
        d.reset()
        // After a reset the next placement is a jump again, and a stale hold is gone.
        val first = scrollOf(d.decide(2, dragging = false, firstVisibleIndex = 7))
        assertEquals(2, first.index)
        assertEquals(false, first.animate)
    }
}
