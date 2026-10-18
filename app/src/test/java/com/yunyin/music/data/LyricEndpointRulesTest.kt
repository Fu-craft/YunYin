package com.yunyin.music.data

import com.yunyin.music.data.net.LyricEndpointRules
import com.yunyin.music.data.net.RequestCacheBust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyric fetch rules: cache-busting, the /lyric/new → /lyric fallback, and the honest empty-state mapping.
 *
 * Live probe against the deployed API (id=186016) showed the two endpoints are not a superset of each other: legacy
 * `/lyric` returned clean line-level LRC (3 629 B) while `/lyric/new` returned 4 707 B whose `lrc` was the mixed
 * YRC/JSON form and whose `yrc` field was absent. Without the fallback such a track can end up with no lyrics even
 * though NetEase plainly has them.
 */
class LyricEndpointRulesTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `lyric requests are cache-busted with a fresh timestamp`() {
        val query = RequestCacheBust.withCacheBust(mapOf("id" to "186016"), now)
        assertEquals("186016", query["id"])
        assertEquals(now.toString(), query["timestamp"])
        assertTrue(RequestCacheBust.isCacheBusted(query))
        assertTrue(
            RequestCacheBust.withCacheBust(emptyMap(), now)["timestamp"] !=
                RequestCacheBust.withCacheBust(emptyMap(), now + 1)["timestamp"],
        )
    }

    @Test
    fun `a mixed word-format lrc without yrc needs the legacy endpoint`() {
        val mixed = """{"t":0,"c":[{"tx":"作词: 周杰伦"}]}
[00:28.950]故事的小黄花"""
        assertTrue(LyricEndpointRules.isMixedWordFormat(mixed))
        assertTrue(LyricEndpointRules.needsLegacyFallback(nolyric = false, lrc = mixed, yrc = null))
    }

    @Test
    fun `a clean word-by-word yrc does not need the legacy endpoint`() {
        assertFalse(
            LyricEndpointRules.needsLegacyFallback(
                nolyric = false,
                lrc = null,
                yrc = "[0,1000](0,500,0)歌(500,500,0)词",
            ),
        )
    }

    @Test
    fun `empty or nolyric payloads need the legacy endpoint`() {
        assertTrue(LyricEndpointRules.needsLegacyFallback(nolyric = true, lrc = null, yrc = null))
        assertTrue(LyricEndpointRules.needsLegacyFallback(nolyric = false, lrc = null, yrc = null))
        assertTrue(LyricEndpointRules.needsLegacyFallback(nolyric = false, lrc = "   ", yrc = null))
    }

    @Test
    fun `a clean line-level lrc needs no fallback`() {
        assertFalse(
            LyricEndpointRules.needsLegacyFallback(
                nolyric = false,
                lrc = "[00:28.950]故事的小黄花",
                yrc = null,
            ),
        )
        assertTrue(LyricEndpointRules.hasLines("[00:28.950]故事的小黄花", null))
        assertFalse(LyricEndpointRules.hasLines(null, null))
    }

    @Test
    fun `nolyric maps to its own message`() {
        assertEquals("该歌曲没有歌词", LyricEndpointRules.emptyMessage(nolyric = true))
        assertEquals("暂无歌词", LyricEndpointRules.emptyMessage(nolyric = false))
    }
}
