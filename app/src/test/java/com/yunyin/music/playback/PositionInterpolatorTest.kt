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

    /**
     * The reported failure this pins down: the karaoke highlight visibly stepped *backwards* for a
     * moment before carrying on. The clock extrapolates up to 400ms past the last poll, so a poll
     * that arrives lower than the current estimate used to rewind it — and the smoothing that feeds
     * the fill applies a negative step instantly, which is what made it visible.
     *
     * A continuous playback clock is monotonic, so a small overshoot is now absorbed by advancing
     * slightly slower than real time instead of moving back.
     */
    @Test
    fun `a small backward correction never rewinds the clock`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)
        assertEquals(300L, clock.positionAt(300, max))

        // The player reports slightly *less* than the estimate — the overshoot case.
        clock.anchor(positionMs = 100, realtimeMs = 300, advancing = true)
        assertEquals("must not rewind to the anchored value", 300L, clock.positionAt(300, max))

        // It stays monotonic frame by frame...
        var previous = 300L
        for (dt in 1..40) {
            val value = clock.positionAt(300 + dt * 16L, max)
            assertTrue("frame $dt went backwards: $value < $previous", value >= previous)
            previous = value
        }
    }

    @Test
    fun `an overshoot is absorbed and the clock re-converges with the audio`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)
        assertEquals(300L, clock.positionAt(300, max))

        // Audio is really at 100ms: an overshoot of 200ms.
        clock.anchor(positionMs = 100, realtimeMs = 300, advancing = true)

        // The player re-polls every 250ms, reporting the true audio position. That is what bounds
        // the error: the estimate can only be 400ms stale, and each poll pulls it back.
        var previous = clock.positionAt(300, max)
        var t = 300L
        var nextPoll = 550L
        while (t < 6_000) {
            t += 16
            if (t >= nextPoll) {
                clock.anchor(positionMs = 100 + (t - 300), realtimeMs = t, advancing = true)
                nextPoll += 250
            }
            val value = clock.positionAt(t, max)
            assertTrue("went backwards at t=$t: $value < $previous", value >= previous)
            // The lead over the true audio position must stay bounded by the extrapolation window.
            val trueAudio = 100 + (t - 300)
            assertTrue(
                "clock led the audio by ${value - trueAudio}ms at t=$t",
                value - trueAudio <= max,
            )
            previous = value
        }

        // Converged, not permanently offset: within a fraction of the extrapolation window.
        val trueAudio = 100 + (t - 300)
        assertTrue(
            "still ${previous - trueAudio}ms ahead after 6s -- the lead did not decay",
            previous - trueAudio < 100,
        )
    }

    /**
     * The reported symptom, reproduced end to end: a true playback clock advancing in real time,
     * polled every 250ms, sampled every frame. Before the fix the displayed value stepped backwards
     * at poll boundaries, which the smoothing then applied instantly and made visible.
     */
    @Test
    fun `the clock is monotonic across many poll cycles`() {
        val clock = PositionInterpolator()
        clock.durationMs = 300_000

        var trueAudio = 0L
        var nextPoll = 0L
        clock.anchor(0, realtimeMs = 0, advancing = true)
        clock.seekTo(0, realtimeMs = 0)

        var previous = 0L
        var t = 0L
        while (t < 20_000) {
            t += 16
            trueAudio += 16
            if (t >= nextPoll) {
                clock.anchor(trueAudio, realtimeMs = t, advancing = true)
                nextPoll = t + 250
            }
            val value = clock.positionAt(t, max)
            assertTrue("t=$t: $value < $previous (stepped backwards)", value >= previous)
            previous = value
        }
    }

    @Test
    fun `a large backward jump is taken as a real discontinuity`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 60_000, realtimeMs = 0, advancing = true)
        assertEquals(60_300L, clock.positionAt(300, max))

        // A loop / track change: far beyond any interpolation error, so it must be applied at once.
        clock.anchor(positionMs = 250, realtimeMs = 300, advancing = true)
        assertEquals(250L, clock.positionAt(300, max))
    }

    @Test
    fun `an explicit seek resets the monotonic guard`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 30_000, realtimeMs = 0, advancing = true)
        assertEquals(30_300L, clock.positionAt(300, max))

        // Seeking backwards is legitimate and must move the clock immediately.
        clock.seekTo(positionMs = 1_000, realtimeMs = 400)
        assertEquals(1_000L, clock.positionAt(400, max))
        assertEquals(1_100L, clock.positionAt(500, max))
    }

    @Test
    fun `pausing mid-overshoot holds still instead of creeping`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)
        assertEquals(300L, clock.positionAt(300, max))

        // Audio has stopped (buffering / paused) and is behind the estimate.
        clock.anchor(positionMs = 100, realtimeMs = 300, advancing = false)
        val held = clock.positionAt(300, max)
        assertEquals(held, clock.positionAt(2_000, max))
        assertEquals(held, clock.positionAt(30_000, max))
    }

    @Test
    fun `a fresh poll re-anchors upward`() {
        val clock = PositionInterpolator()
        clock.durationMs = 200_000
        clock.anchor(positionMs = 0, realtimeMs = 0, advancing = true)
        assertEquals(300L, clock.positionAt(300, max))

        // Player reports the audio has advanced further than extrapolated — follow it exactly.
        clock.anchor(positionMs = 400, realtimeMs = 300, advancing = true)
        assertEquals(400L, clock.positionAt(300, max))
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
