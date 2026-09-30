package com.yunyin.music.ui

import com.yunyin.music.playback.PlayerControllerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the request-identity rules that stop a slow response from overwriting newer state.
 *
 * These were real defects: switching playlist A→B quickly could leave A's tracks under B's header, a
 * late search could replace the results of a newer one, and a previous song's member-only notice could
 * survive into the next track. The rules are pure predicates here so they can be exercised without a
 * network or a player.
 */
class StaleResultRulesTest {

    @Test
    fun `a stale collection response is rejected`() {
        // Request 7 finished after request 8 had started: it must not be applied.
        assertFalse(AppViewModel.collectionResultApplies(requestId = 7, currentRequestId = 8, shownId = 5, resultId = 5))
        assertTrue(AppViewModel.collectionResultApplies(requestId = 8, currentRequestId = 8, shownId = 5, resultId = 5))
    }

    @Test
    fun `a response for a collection that is no longer shown is rejected`() {
        // Same request generation, but the user has since opened a different page.
        assertFalse(AppViewModel.collectionResultApplies(requestId = 8, currentRequestId = 8, shownId = 9, resultId = 5))
    }

    @Test
    fun `the liked list is identified by its zero id`() {
        assertTrue(AppViewModel.collectionResultApplies(requestId = 1, currentRequestId = 1, shownId = 0, resultId = 0))
        assertFalse(AppViewModel.collectionResultApplies(requestId = 1, currentRequestId = 1, shownId = 0, resultId = 5))
    }

    @Test
    fun `a trial result is only accepted for the track that asked for it`() {
        // The track changed while the stream lookup was in flight.
        assertFalse(PlayerControllerRules.trialResultApplies(requestId = 3, currentRequestId = 4, forTrackId = 100, currentTrackId = 100))
        assertFalse(PlayerControllerRules.trialResultApplies(requestId = 4, currentRequestId = 4, forTrackId = 100, currentTrackId = 200))
        assertTrue(PlayerControllerRules.trialResultApplies(requestId = 4, currentRequestId = 4, forTrackId = 100, currentTrackId = 100))
    }

    @Test
    fun `a null track clears trial state only for the current request`() {
        assertEquals(true, PlayerControllerRules.clearTrial(requestId = 4, currentRequestId = 4))
        assertEquals(false, PlayerControllerRules.clearTrial(requestId = 3, currentRequestId = 4))
    }
}
