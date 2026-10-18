package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.net.PlaylistPagingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batching/order/partial-page/cap rules for the playlist loader.
 *
 * The bug was one fixed `limit = 200`; these rules are what make an arbitrary-size playlist load completely and
 * still fail safe if the server's paging metadata lies.
 */
class PlaylistPagingRulesTest {

    private fun track(id: Long) = Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )

    @Test
    fun `ids are chunked into fixed-size batches`() {
        val ids = (1L..1200L).toList()
        val chunks = PlaylistPagingRules.chunks(ids)
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun `assembly follows the id order and drops missing ids without shifting`() {
        val ids = listOf(3L, 1L, 2L, 99L)
        val byId = mapOf(1L to track(1), 2L to track(2), 3L to track(3))
        assertEquals(listOf(3L, 1L, 2L), PlaylistPagingRules.assemble(ids, byId).map { it.id })
    }

    @Test
    fun `a playlist may contain the same song twice`() {
        val ids = listOf(5L, 5L, 6L)
        val byId = mapOf(5L to track(5), 6L to track(6))
        assertEquals(listOf(5L, 5L, 6L), PlaylistPagingRules.assemble(ids, byId).map { it.id })
    }

    @Test
    fun `a full page continues and a short page stops`() {
        val full = PlaylistPagingRules.pageDecision(returned = 1000, requested = 1000, loaded = 1000, pageIndex = 0)
        assertFalse(full.stop)
        assertFalse(full.capped)
        val short = PlaylistPagingRules.pageDecision(returned = 200, requested = 1000, loaded = 1200, pageIndex = 1)
        assertTrue(short.stop)
        assertFalse(short.capped)
    }

    @Test
    fun `the loop caps at max pages instead of never finishing`() {
        val capped = PlaylistPagingRules.pageDecision(
            returned = 1000, requested = 1000, loaded = 19_000, pageIndex = PlaylistPagingRules.MAX_PAGES - 1,
        )
        assertTrue(capped.stop)
        assertTrue(capped.capped)
    }

    @Test
    fun `the loop caps at max tracks`() {
        val capped = PlaylistPagingRules.pageDecision(
            returned = 1000, requested = 1000, loaded = PlaylistPagingRules.MAX_TRACKS, pageIndex = 0,
        )
        assertTrue(capped.stop)
        assertTrue(capped.capped)
    }
}
