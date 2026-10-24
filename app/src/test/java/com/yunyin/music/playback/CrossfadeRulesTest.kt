// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dual-player crossfade decisions: eligibility, deadline, equal-power curve and abort predicates. */
class CrossfadeRulesTest {

    @Test
    fun `duration choices map to milliseconds with a 6s default`() {
        assertEquals(listOf(3, 6, 9, 12), CrossfadeRules.DURATIONS_SECONDS)
        assertEquals(6_000L, CrossfadeRules.durationMs(CrossfadeRules.DEFAULT_SECONDS))
        assertEquals(0L, CrossfadeRules.durationMs(0))
        assertEquals(12_000L, CrossfadeRules.durationMs(99))
    }

    @Test
    fun `eligibility follows queue position and repeat mode`() {
        assertEquals(1, CrossfadeRules.nextIndex(0, 3, Player.REPEAT_MODE_OFF))
        assertNull(CrossfadeRules.nextIndex(2, 3, Player.REPEAT_MODE_OFF))
        assertEquals(0, CrossfadeRules.nextIndex(2, 3, Player.REPEAT_MODE_ALL))
        assertNull(CrossfadeRules.nextIndex(0, 1, Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `preload starts only inside the fade plus margin window and with all preconditions`() {
        val fade = 6_000L
        assertTrue(CrossfadeRules.shouldStartPreload(true, true, false, true, 6_900, fade, 1, Player.REPEAT_MODE_OFF))
        assertFalse(CrossfadeRules.shouldStartPreload(true, true, false, true, 20_000, fade, 1, Player.REPEAT_MODE_OFF))
        assertFalse(CrossfadeRules.shouldStartPreload(false, true, false, true, 6_900, fade, 1, Player.REPEAT_MODE_OFF))
        assertFalse(CrossfadeRules.shouldStartPreload(true, true, true, true, 6_900, fade, 1, Player.REPEAT_MODE_OFF))
        assertFalse(CrossfadeRules.shouldStartPreload(true, true, false, true, 6_900, fade, 1, Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `fade begins only when ready and inside the window`() {
        assertTrue(CrossfadeRules.shouldStartFade(true, false, 5_900, 6_000, true))
        assertFalse(CrossfadeRules.shouldStartFade(false, false, 5_900, 6_000, true))
        assertFalse(CrossfadeRules.shouldStartFade(true, false, 30_000, 6_000, true))
        assertFalse(CrossfadeRules.shouldStartFade(true, false, 5_900, 6_000, false))
    }

    @Test
    fun `equal-power gains are monotone and sum of squares is one`() {
        var previousOut = 1.1f
        var previousIn = -0.1f
        for (i in 0..20) {
            val (out, incoming) = CrossfadeRules.gains(i / 20f)
            assertTrue(out <= previousOut + 1e-4f)
            assertTrue(incoming >= previousIn - 1e-4f)
            assertEquals(1f, out * out + incoming * incoming, 1e-3f)
            previousOut = out
            previousIn = incoming
        }
        assertEquals(1f, CrossfadeRules.gains(0f).first, 1e-4f)
        assertEquals(0f, CrossfadeRules.gains(0f).second, 1e-4f)
        assertEquals(1f, CrossfadeRules.gains(1f).second, 1e-4f)
    }

    @Test
    fun `abort predicates cover pause, repeat-one and backward seek`() {
        assertTrue(CrossfadeRules.shouldAbort(false, Player.REPEAT_MODE_OFF, 100_000, 100_000))
        assertTrue(CrossfadeRules.shouldAbort(true, Player.REPEAT_MODE_ONE, 100_000, 100_000))
        assertTrue(CrossfadeRules.shouldAbort(true, Player.REPEAT_MODE_OFF, 100_000, 90_000))
        assertFalse(CrossfadeRules.shouldAbort(true, Player.REPEAT_MODE_OFF, 100_000, 100_500))
        assertTrue(CrossfadeRules.preloadDeadlineExpired(true, false))
        assertFalse(CrossfadeRules.preloadDeadlineExpired(true, true))
    }
}
