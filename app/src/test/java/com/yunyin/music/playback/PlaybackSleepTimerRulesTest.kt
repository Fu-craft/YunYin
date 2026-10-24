// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deadline maths for the sleep timer: 15 minutes is 15 minutes, remaining rounds up, fire at or after the deadline. */
class PlaybackSleepTimerRulesTest {

    private val now = 1_000_000L

    @Test
    fun `the deadline is now plus the chosen minutes`() {
        assertEquals(now + 15 * 60_000L, PlaybackSleepTimerRules.deadline(now, 15))
        assertEquals(now + 60 * 60_000L, PlaybackSleepTimerRules.deadline(now, 60))
    }

    @Test
    fun `remaining minutes round up and never go negative`() {
        assertEquals(15, PlaybackSleepTimerRules.remainingMinutes(now, now + 15 * 60_000L))
        assertEquals(1, PlaybackSleepTimerRules.remainingMinutes(now, now + 1L))
        assertEquals(0, PlaybackSleepTimerRules.remainingMinutes(now, now - 1L))
        assertNull(PlaybackSleepTimerRules.remainingMinutes(now, null))
    }

    @Test
    fun `the timer fires at or after the deadline`() {
        assertFalse(PlaybackSleepTimerRules.shouldFire(now, now + 60_000L))
        assertTrue(PlaybackSleepTimerRules.shouldFire(now, now))
        assertTrue(PlaybackSleepTimerRules.shouldFire(now, now - 1L))
        assertFalse(PlaybackSleepTimerRules.shouldFire(now, null))
    }

    @Test
    fun `the picker options match the requested list`() {
        assertEquals(listOf(15, 30, 45, 60), PlaybackSleepTimerRules.OPTION_MINUTES)
        assertEquals(0, PlaybackSleepTimerRules.OFF)
        assertEquals(-1, PlaybackSleepTimerRules.END_OF_TRACK)
    }
}
