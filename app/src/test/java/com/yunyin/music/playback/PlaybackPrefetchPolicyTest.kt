// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prefetch policy: which track is warmed, and the safety rules around it.
 *
 * A prefetch is only worthwhile for the queue's immediate next id, must never target the live track, and must never
 * interfere with the live lookup's cancellation. The "cannot cancel a live lookup" property is structural (the
 * prefetch job is never passed to `cancelPending`); what is testable here is the selection rule that keeps it from
 * touching the current track or stacking jobs.
 */
class PlaybackPrefetchPolicyTest {

    @Test
    fun `the next queue id is selected`() {
        assertEquals(2L, PlaybackPrefetchPolicy.nextTrackId(listOf(1L, 2L, 3L), 0))
        assertEquals(3L, PlaybackPrefetchPolicy.nextTrackId(listOf(1L, 2L, 3L), 1))
    }

    @Test
    fun `the end of the queue has nothing to prefetch`() {
        assertNull(PlaybackPrefetchPolicy.nextTrackId(listOf(1L, 2L), 1))
        assertNull(PlaybackPrefetchPolicy.nextTrackId(emptyList(), 0))
        assertNull(PlaybackPrefetchPolicy.nextTrackId(listOf(1L), 0))
    }

    @Test
    fun `a repeated adjacent id is not warmed twice`() {
        assertNull(PlaybackPrefetchPolicy.nextTrackId(listOf(7L, 7L), 0))
    }

    @Test
    fun `the live track is never prefetched`() {
        assertFalse(PlaybackPrefetchPolicy.shouldStart(currentTrackId = 5L, nextTrackId = 5L, inFlightPrefetchTrackId = null))
        assertFalse(PlaybackPrefetchPolicy.shouldStart(currentTrackId = 5L, nextTrackId = null, inFlightPrefetchTrackId = null))
    }

    @Test
    fun `only one prefetch is in flight`() {
        assertFalse(
            PlaybackPrefetchPolicy.shouldStart(
                currentTrackId = 5L,
                nextTrackId = 6L,
                inFlightPrefetchTrackId = 6L,
            ),
        )
        assertTrue(
            PlaybackPrefetchPolicy.shouldStart(
                currentTrackId = 5L,
                nextTrackId = 6L,
                inFlightPrefetchTrackId = null,
            ),
        )
    }

    @Test
    fun `the delay leaves the current track's start alone`() {
        // A prefetch that started immediately could compete with the current track's own POST/buffer window.
        assertTrue(PlaybackPrefetchPolicy.PREFETCH_DELAY_MS >= 5_000L)
    }
}
