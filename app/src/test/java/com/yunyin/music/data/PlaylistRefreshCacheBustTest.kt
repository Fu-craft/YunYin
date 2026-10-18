package com.yunyin.music.data

import com.yunyin.music.data.playlist.PlaylistRequestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-busting for playlist reads.
 *
 * NeteaseCloudMusicApi answers `/user/playlist` (and detail/track reads) from a server-side cache unless the request
 * carries a `timestamp`. Without it a create succeeds and the immediate refetch still returns the old list — the
 * "创建了歌单但不显示" report this test exists to prevent from recurring silently.
 */
class PlaylistRefreshCacheBustTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `a read query gains a fresh timestamp`() {
        val query = PlaylistRequestRules.withCacheBust(mapOf("uid" to "42", "limit" to "100"), now)
        assertEquals("42", query["uid"])
        assertEquals("100", query["limit"])
        assertEquals(now.toString(), query["timestamp"])
        assertTrue(PlaylistRequestRules.isCacheBusted(query))
    }

    @Test
    fun `consecutive refetches use different timestamps`() {
        val first = PlaylistRequestRules.withCacheBust(emptyMap(), now)
        val second = PlaylistRequestRules.withCacheBust(emptyMap(), now + 1)
        assertTrue(first["timestamp"] != second["timestamp"])
    }

    @Test
    fun `an incoming timestamp is replaced, not duplicated`() {
        val query = PlaylistRequestRules.withCacheBust(mapOf("timestamp" to "1"), now)
        assertEquals(now.toString(), query["timestamp"])
        assertEquals(1, query.keys.count { it == "timestamp" })
    }

    @Test
    fun `a query without a timestamp is not cache-busted`() {
        assertFalse(PlaylistRequestRules.isCacheBusted(mapOf("uid" to "42")))
        assertFalse(PlaylistRequestRules.isCacheBusted(mapOf("timestamp" to "0")))
    }
}
