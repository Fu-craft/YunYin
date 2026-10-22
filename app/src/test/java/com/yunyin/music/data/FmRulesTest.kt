package com.yunyin.music.data

import com.yunyin.music.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FmRulesTest {
    private fun t(id: Long) = Track(id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L, coverUrl = null, durationMs = 1_000L)

    @Test
    fun `refill starts before the queue runs dry`() {
        assertTrue(FmRules.shouldRefill(currentIndex = 8, queueSize = 10))
        assertTrue(FmRules.shouldRefill(currentIndex = 9, queueSize = 10))
        assertFalse(FmRules.shouldRefill(currentIndex = 0, queueSize = 10))
        assertTrue(FmRules.shouldRefill(currentIndex = 0, queueSize = 0))
    }

    @Test
    fun `appending a batch never duplicates ids`() {
        val merged = FmRules.appendUnique(listOf(t(1), t(2)), listOf(t(2), t(3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `album subscribed seeds only from a successful read`() {
        assertTrue(FmRules.albumSubscribedSeeded(readSucceeded = true, isSub = true))
        assertFalse(FmRules.albumSubscribedSeeded(readSucceeded = true, isSub = false))
        assertFalse(FmRules.albumSubscribedSeeded(readSucceeded = false, isSub = false))
    }

    @Test
    fun `fm session starts and stops`() {
        assertTrue(FmRules.start(123L).active)
        assertFalse(FmRules.stop().active)
    }
}
