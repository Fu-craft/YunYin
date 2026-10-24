// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.net.AnimatedArtwork
import com.yunyin.music.data.net.AnimatedArtworkLruCache
import com.yunyin.music.data.net.AnimatedArtworkRules
import com.yunyin.music.data.net.AnimatedArtworkSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the animated-artwork key, cache, parsing, inert-mode and dedupe contracts. */
class AnimatedArtworkRulesTest {

    private fun art(url: String = "https://example.test/a.m3u8") = AnimatedArtwork(
        url = url, urlTall = null, artist = "Artist", album = "Album", isCached = false,
    )

    @Test
    fun `cache keys normalise whitespace, case and require artist and album`() {
        assertEquals("周杰伦\u0000范特西", AnimatedArtworkRules.cacheKey("  周杰伦 ", " 范特西 "))
        assertEquals("the weeknd\u0000after hours", AnimatedArtworkRules.cacheKey("The   Weeknd", "After\tHours"))
        assertNull(AnimatedArtworkRules.cacheKey("", "Album"))
        assertNull(AnimatedArtworkRules.cacheKey("Artist", "  "))
    }

    @Test
    fun `blank or invalid base URL is inert`() {
        assertFalse(AnimatedArtworkRules.isAvailable(""))
        assertFalse(AnimatedArtworkRules.isAvailable("  "))
        assertFalse(AnimatedArtworkRules.isAvailable("ftp://example.test"))
        assertTrue(AnimatedArtworkRules.isAvailable("https://art.example.test"))
        runBlocking {
            val repo = AnimatedArtworkRepository(source = null)
            assertFalse(repo.available)
            assertNull(repo.resolve("Artist", "Album"))
        }
    }

    @Test
    fun `json parsing tolerates missing and literal null fields`() {
        val full = AnimatedArtworkRules.parse(
            """{"url":"https://example.test/a.m3u8","url_tall":"https://example.test/tall.m3u8","artist":"A","album":"B","isCached":true}""",
        )
        assertNotNull(full)
        assertEquals("https://example.test/a.m3u8", full!!.url)
        assertEquals("https://example.test/tall.m3u8", full.urlTall)
        assertTrue(full.isCached)

        val nullish = AnimatedArtworkRules.parse(
            """{"url":"https://example.test/a.m3u8","url_tall":"null","artist":null,"album":null}""",
        )
        assertNotNull(nullish)
        assertNull(nullish!!.urlTall)
        assertEquals("", nullish.artist)
        assertFalse(nullish.isCached)

        assertNull(AnimatedArtworkRules.parse("""{"url":""}"""))
        assertNull(AnimatedArtworkRules.parse("""{"url":"null"}"""))
        assertNull(AnimatedArtworkRules.parse("not json"))
        assertNull(AnimatedArtworkRules.parse(null))
    }

    @Test
    fun `lru keeps at most 64 entries and caches negative results`() {
        val cache = AnimatedArtworkLruCache(64)
        repeat(65) { cache.put("k$it", art("https://example.test/$it.m3u8")) }
        assertEquals(64, cache.size())
        assertNull(cache.get("k0"))
        assertNotNull(cache.get("k1"))
        cache.put("missing", null)
        val negative = cache.get("missing")
        assertNotNull(negative)
        assertNull(negative!!.value)
    }

    @Test
    fun `fallback decision requires enabled, resolved and non-blank url`() {
        assertTrue(AnimatedArtworkRules.shouldShowVideo(true, art()))
        assertFalse(AnimatedArtworkRules.shouldShowVideo(false, art()))
        assertFalse(AnimatedArtworkRules.shouldShowVideo(true, null))
        assertFalse(AnimatedArtworkRules.shouldShowVideo(true, art(" ")))
    }

    @Test
    fun `in-flight dedupe shares one request and caller cancellation does not orphan it`() = runBlocking {
        var calls = 0
        val source = object : AnimatedArtworkSource {
            override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
                calls++
                delay(150)
                return art()
            }
        }
        val repo = AnimatedArtworkRepository(source)
        val first = async { repo.resolve("Artist", "Album") }
        delay(20)
        val second = async { repo.resolve("Artist", "Album") }
        assertEquals(art(), first.await())
        assertEquals(art(), second.await())
        assertEquals(1, calls)
        // A caller leaving while a different request is in flight must not cancel the shared work
        // or leave a duplicate in flight. The repository-owned scope finishes and caches it.
        val leaving = async { repo.resolve("Leaving", "Album") }
        delay(20)
        leaving.cancel()
        delay(250)
        assertEquals(2, calls)
        assertNotNull(repo.cached(AnimatedArtworkRules.cacheKey("Leaving", "Album")!!))
        repo.resolve("Leaving", "Album")
        assertEquals(2, calls)
    }
}
