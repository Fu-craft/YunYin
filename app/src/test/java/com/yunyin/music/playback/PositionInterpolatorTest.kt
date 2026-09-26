package com.yunyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyric timing clock.
 *
 * The reported failure this pins down: lines began *before the lead-in had finished*. The
 * cause was that the interpolated position kept advancing while ExoPlayer was buffering — the
 * player reports `isPlaying == true` in that state, but its position is frozen, so
 * extrapolation ran the lyric clock ahead of the audio.
 */
class PositionInterpolatorTest {

    private val max = PositionInterpolator.MAX_EXTRAPOLATION_MS

    @Test
    fun `does not advance while buffering`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        // Buffering: playWhenReady is true but audio is not rendering.
        clock.anchor(positionMs = 0, realtimeMs = 1_000, advancing = false)

        // A full second of wall clock passes with no audio progress.
        assertEquals(0L, clock.positionAt(1_500, max))
        assertEquals(0L, clock.positionAt(2_000, max))
        assertEquals("must never jump ahead into the first line", 0L, clock.positionAt(9_000, max))
    }

    @Test
    fun `advances smoothly while playing`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 5_000, realtimeMs = 10_000, advancing = true)

        assertEquals(5_100L, clock.positionAt(10_100, max))
        assertEquals(5_250L, clock.positionAt(10_250, max))
    }

    @Test
    fun `extrapolation is bounded so a stalled poll cannot drift`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)

        // Even if no new poll arrives for a long time, the reported position stays bounded.
        assertEquals(max, clock.positionAt(60_000, max))
        assertEquals(max, clock.positionAt(10_000_000, max))
    }

    @Test
    fun `position never exceeds the duration`() {
        val clock = PositionInterpolator()
        clock.durationMs = 1_000
        clock.anchor(positionMs = 990, realtimeMs = 0, advancing = true)
        assertEquals(1_000L, clock.positionAt(500, max))
    }

    @Test
    fun `a fresh poll re-anchors to the authoritative value`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)
        assertEquals(300L, clock.positionAt(300, max))

        // Player reports a corrected position (e.g. after a seek or a stall).
        clock.anchor(positionMs = 100, realtimeMs = 300, advancing = true)
        assertEquals(100L, clock.positionAt(300, max))
    }

    @Test
    fun `a seek jumps immediately and stays put while paused`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.seekTo(positionMs = 42_000, realtimeMs = 1_000)
        // Not advancing, so the value is returned as-is.
        assertEquals(42_000L, clock.positionAt(5_000, max))
    }

    @Test
    fun `time going backwards does not rewind the clock`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 10_000, realtimeMs = 5_000, advancing = true)
        // A non-monotonic sample must clamp to zero elapsed, not produce a negative offset.
        val value = clock.positionAt(4_000, max)
        assertTrue("must not rewind", value >= 10_000L)
        assertEquals(10_000L, value)
    }

    @Test
    fun `transitioning from buffering to playing resumes cleanly`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(0, realtimeMs = 0, advancing = false)
        // Still held at 0 through the buffering window.
        assertEquals(0L, clock.positionAt(3_000, max))
        // Audio starts: re-anchor and advance from there.
        clock.anchor(0, realtimeMs = 3_000, advancing = true)
        assertEquals(0L, clock.positionAt(3_000, max))
        assertEquals(150L, clock.positionAt(3_150, max))
    }
}
