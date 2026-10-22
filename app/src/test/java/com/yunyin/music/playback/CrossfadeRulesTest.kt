package com.yunyin.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary fade eligibility and gain curve (pure; the player only applies the value). */
class CrossfadeRulesTest {

    @Test
    fun `a next item exists in queue order or when repeat-all loops`() {
        assertTrue(CrossfadeRules.hasNextItem(0, 2, Player.REPEAT_MODE_OFF))
        assertFalse(CrossfadeRules.hasNextItem(1, 2, Player.REPEAT_MODE_OFF))
        assertTrue(CrossfadeRules.hasNextItem(1, 2, Player.REPEAT_MODE_ALL))
        assertFalse(CrossfadeRules.hasNextItem(0, 1, Player.REPEAT_MODE_ALL))
        assertFalse(CrossfadeRules.hasNextItem(2, 3, Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `the feature off or no boundary means full volume`() {
        assertEquals(1f, CrossfadeRules.gain(1_000, 200_000, enabled = false, hasNext = true))
        assertEquals(1f, CrossfadeRules.gain(1_000, 200_000, enabled = true, hasNext = false))
    }

    @Test
    fun `head and tail ramp, middle stays at full volume`() {
        val duration = 200_000L
        assertTrue(CrossfadeRules.gain(0, duration, true, true) < 0.05f)
        assertEquals(1f, CrossfadeRules.gain(duration / 2, duration, true, true))
        assertTrue(CrossfadeRules.gain(duration - 1, duration, true, true) < 0.05f)
        assertEquals(1f, CrossfadeRules.gain(10_000, duration, true, true))
    }

    @Test
    fun `short tracks use a shorter fade instead of fading the whole song`() {
        val short = 1_500L
        val gain = CrossfadeRules.gain(short / 2, short, true, true)
        assertTrue("midpoint of a 1.5s track should be near full volume, was $gain", gain > 0.9f)
    }
}
