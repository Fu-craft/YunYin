// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxResolveCacheRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded resolution cache.
 *
 * Re-playing a track must be instant, a failed resolution must not be remembered for minutes, and the cache must
 * never grow without bound. These are the three rules that make the cache safe, tested without Android or network.
 */
class ResolutionCacheTest {

    private class Box(val value: String?)

    private fun cache(max: Int = 3) = com.yunyin.music.data.lxsource.ResolutionCache<Box>(max)

    private val now = 1_000_000L

    @Test
    fun `a stored value is returned within its ttl`() {
        val cache = cache()
        cache.put("1:exhigh", Box("https://cdn/a"), now)
        assertEquals("https://cdn/a", cache.get("1:exhigh", now + 500, { 60_000 })?.value)
    }

    @Test
    fun `a value expires after its ttl`() {
        val cache = cache()
        cache.put("1:exhigh", Box("https://cdn/a"), now)
        assertNull(cache.get("1:exhigh", now + 60_001, { 60_000 }))
        // Expired entries are removed, not just hidden.
        assertEquals(0, cache.size())
    }

    @Test
    fun `success and failure can use different ttls`() {
        val cache = cache()
        cache.put("ok", Box("url"), now)
        cache.put("bad", Box(null), now)
        val successTtl = LxResolveCacheRules.SUCCESS_TTL_MS
        val negativeTtl = LxResolveCacheRules.NEGATIVE_TTL_MS
        assertTrue(successTtl > negativeTtl)
        assertEquals("url", cache.get("ok", now + successTtl, { successTtl })?.value)
        assertNull(cache.get("bad", now + negativeTtl + 1, { negativeTtl }))
    }

    @Test
    fun `the lru bound evicts the oldest entry`() {
        val cache = cache(max = 2)
        cache.put("a", Box("1"), now)
        cache.put("b", Box("2"), now)
        cache.put("c", Box("3"), now) // a is evicted
        assertEquals(2, cache.size())
        assertNull(cache.get("a", now, { 60_000 }))
        assertEquals("2", cache.get("b", now, { 60_000 })?.value)
        assertEquals("3", cache.get("c", now, { 60_000 })?.value)
    }

    @Test
    fun `reading an entry keeps it from being evicted first`() {
        val cache = cache(max = 2)
        cache.put("a", Box("1"), now)
        cache.put("b", Box("2"), now)
        cache.get("a", now, { 60_000 }) // a becomes most recently used
        cache.put("c", Box("3"), now) // b is evicted
        assertEquals("1", cache.get("a", now, { 60_000 })?.value)
        assertNull(cache.get("b", now, { 60_000 }))
    }

    @Test
    fun `invalidation removes every quality of a track`() {
        val cache = cache()
        cache.put("42:exhigh", Box("a"), now)
        cache.put("42:lossless", Box("b"), now)
        cache.put("43:exhigh", Box("c"), now)
        cache.removeWhere { key -> key.startsWith("42:") }
        assertNull(cache.get("42:exhigh", now, { 60_000 }))
        assertNull(cache.get("42:lossless", now, { 60_000 }))
        assertEquals("c", cache.get("43:exhigh", now, { 60_000 })?.value)
    }

    @Test
    fun `a cached null is distinguishable from a miss for the negative path`() {
        val cache = cache()
        cache.put("bad", Box(null), now)
        assertEquals(1, cache.size())
        // The caller of the real cache treats a fresh Box(null) as "cached failure, do not retry yet".
        assertEquals(null, cache.get("bad", now, { 60_000 })?.value)
        assertNull(cache.get("missing", now, { 60_000 }))
    }
}
