package com.yunyin.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flyme ticker rate limit and track-change delay. */
class FlymeLyricThrottleTest {

    private val start = 10_000L

    @Test
    fun `the first line after a track change waits one second`() {
        var state = FlymeLyricThrottle.onTrackChanged(FlymeLyricThrottle.State(), "A", start)
        assertFalse(FlymeLyricThrottle.canPost(state, start + 500))
        assertFalse(FlymeLyricThrottle.canPost(state, start + 999))
        assertTrue(FlymeLyricThrottle.canPost(state, start + 1_000))
    }

    @Test
    fun `posts are coalesced to one per second and intermediate lines are dropped`() {
        var state = FlymeLyricThrottle.onTrackChanged(FlymeLyricThrottle.State(), "A", start)
        assertTrue(FlymeLyricThrottle.canPost(state, start + 1_000))
        state = FlymeLyricThrottle.markPosted(state, start + 1_000)
        assertFalse(FlymeLyricThrottle.canPost(state, start + 1_400))
        assertFalse(FlymeLyricThrottle.canPost(state, start + 1_999))
        assertTrue(FlymeLyricThrottle.canPost(state, start + 2_000))
    }

    @Test
    fun `a new track resets the delay but not the rate window`() {
        var state = FlymeLyricThrottle.onTrackChanged(FlymeLyricThrottle.State(), "A", start)
        state = FlymeLyricThrottle.markPosted(state, start + 1_000)
        val changed = FlymeLyricThrottle.onTrackChanged(state, "B", start + 1_200)
        assertFalse(FlymeLyricThrottle.canPost(changed, start + 1_500))
        assertTrue(FlymeLyricThrottle.canPost(changed, start + 2_200))
    }

    @Test
    fun `an unchanged track does not re-arm the delay`() {
        var state = FlymeLyricThrottle.onTrackChanged(FlymeLyricThrottle.State(), "A", start)
        state = FlymeLyricThrottle.markPosted(state, start + 1_000)
        val same = FlymeLyricThrottle.onTrackChanged(state, "A", start + 5_000)
        assertTrue(FlymeLyricThrottle.canPost(same, start + 5_000))
    }
}
