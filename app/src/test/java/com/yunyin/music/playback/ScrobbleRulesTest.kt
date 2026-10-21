package com.yunyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrobble threshold rule: real accumulated playback only, no seek-to-end, no paused time, once per track.
 */
class ScrobbleRulesTest {

    private fun tick(progress: ScrobbleRules.Progress, position: Long, playing: Boolean = true) =
        ScrobbleRules.onTick(progress, position, playing)

    @Test
    fun `crossing the threshold mid-play is reportable once`() {
        val p = ScrobbleRules.Progress(playedMs = ScrobbleRules.thresholdMs(200_000L))
        assertTrue(ScrobbleRules.shouldReport(p, 200_000L))
        assertFalse(ScrobbleRules.shouldReport(p.copy(reported = true), 200_000L))
    }

    @Test
    fun `anonymous and logged-out sessions never send`() {
        assertTrue(ScrobbleRules.shouldSend(enabled = true, hasCookie = true, anonymous = false))
        assertFalse(ScrobbleRules.shouldSend(enabled = false, hasCookie = true, anonymous = false))
        assertFalse(ScrobbleRules.shouldSend(enabled = true, hasCookie = false, anonymous = false))
        assertFalse(ScrobbleRules.shouldSend(enabled = true, hasCookie = true, anonymous = true))
    }

    @Test
    fun `half the duration or four minutes, whichever comes first`() {
        assertEquals(90_000L, ScrobbleRules.thresholdMs(180_000L))
        assertEquals(240_000L, ScrobbleRules.thresholdMs(900_000L))
        assertEquals(240_000L, ScrobbleRules.thresholdMs(0L))
    }

    @Test
    fun `accumulates small forward deltas while playing`() {
        var p = ScrobbleRules.Progress()
        p = tick(p, 1_000)
        p = tick(p, 2_000)
        p = tick(p, 2_500)
        assertEquals(2_500L, p.playedMs)
        assertTrue(ScrobbleRules.shouldReport(p, 5_000L))
    }

    @Test
    fun `a seek to the end does not count as a play`() {
        var p = ScrobbleRules.Progress()
        p = tick(p, 1_000)
        p = tick(p, 200_000) // jump, treated as a seek
        assertEquals(1_000L, p.playedMs)
        assertFalse(ScrobbleRules.shouldReport(p, 210_000L))
    }

    @Test
    fun `paused polls add nothing and resume counting from the new position`() {
        var p = ScrobbleRules.Progress()
        p = tick(p, 1_000)
        p = tick(p, 1_500, playing = false)
        assertEquals(1_000L, p.playedMs)
        p = tick(p, 2_000) // still only +500, the paused jump is not counted
        assertEquals(1_500L, p.playedMs)
    }

    @Test
    fun `an unknown duration falls back to four minutes`() {
        var p = ScrobbleRules.Progress()
        p = p.copy(playedMs = 239_000L)
        assertFalse(ScrobbleRules.shouldReport(p, 0L))
        assertTrue(ScrobbleRules.shouldReport(p.copy(playedMs = 240_000L), 0L))
    }

    @Test
    fun `only one report per track`() {
        val p = ScrobbleRules.Progress(playedMs = 300_000L, reported = true)
        assertFalse(ScrobbleRules.shouldReport(p, 600_000L))
    }

    @Test
    fun `the reported time is clamped into the track`() {
        val p = ScrobbleRules.Progress(playedMs = 300_000L)
        assertEquals(180, ScrobbleRules.reportSeconds(p, 180_000L))
        assertEquals(300, ScrobbleRules.reportSeconds(p, 0L))
    }
}
