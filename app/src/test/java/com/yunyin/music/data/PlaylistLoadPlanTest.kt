// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.net.PlaylistLoadPlan
import com.yunyin.music.data.playlist.PlaylistTrackCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Two-stage page plan, contiguous assembly and the memory/disk TTL cache.
 *
 * Measured motivation: skipping the 2.5 MB `/playlist/detail` call and rendering a 200-row first page before the
 * remaining 500-row pages (2 concurrent) is what makes a 500+ track playlist feel fast.
 */
class PlaylistLoadPlanTest {

    private fun track(id: Long) = Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )

    @Test
    fun `a 566-track playlist is one first page plus one remainder page`() {
        assertEquals(listOf(0 to 200, 200 to 500), PlaylistLoadPlan.plan(566).map { it.offset to it.limit })
    }

    @Test
    fun `a 1267-track playlist adds one more 500 page`() {
        assertEquals(
            listOf(0 to 200, 200 to 500, 700 to 500, 1200 to 500),
            PlaylistLoadPlan.plan(1267).map { it.offset to it.limit },
        )
    }

    @Test
    fun `the plan is capped`() {
        val plan = PlaylistLoadPlan.plan(1_000_000)
        assertTrue(plan.size <= PlaylistLoadPlan.MAX_PAGES)
        assertTrue(plan.last().offset < PlaylistLoadPlan.MAX_TRACKS)
    }

    @Test
    fun `assembly is contiguous, ordered and keeps duplicates`() {
        val plan = PlaylistLoadPlan.plan(566)
        val pages = mapOf(0 to listOf(track(1), track(2)), 200 to listOf(track(3), track(3)))
        assertEquals(listOf(1L, 2L, 3L, 3L), PlaylistLoadPlan.contiguous(pages, plan).map { it.id })
        assertEquals(
            listOf(1L, 2L),
            PlaylistLoadPlan.contiguous(
                mapOf(0 to listOf(track(1), track(2)), 700 to listOf(track(9))),
                PlaylistLoadPlan.plan(1267),
            ).map { it.id },
        )
    }

    @Test
    fun `the cache serves fresh entries and expires after the ttl`() {
        val dir = Files.createTempDirectory("playlist-cache").toFile()
        var now = 1_000_000L
        val cache = PlaylistTrackCache(dir, ttlMs = 1_000L, clock = { now })
        cache.put(42L, listOf(track(1), track(2)))
        assertEquals(listOf(1L, 2L), cache.get(42L)?.map { it.id })
        now += 1_001L
        assertNull(cache.get(42L))
    }

    @Test
    fun `the disk half survives a new cache instance`() {
        val dir = Files.createTempDirectory("playlist-cache2").toFile()
        PlaylistTrackCache(dir).put(7L, listOf(track(5)))
        assertEquals(listOf(5L), PlaylistTrackCache(dir).get(7L)?.map { it.id })
    }

    @Test
    fun `invalidation drops memory and disk`() {
        val dir = Files.createTempDirectory("playlist-cache3").toFile()
        val cache = PlaylistTrackCache(dir)
        cache.put(9L, listOf(track(1)))
        cache.invalidate(9L)
        assertNull(cache.get(9L))
        assertEquals(0, dir.listFiles()?.count { it.name.startsWith("9.") } ?: 0)
    }
}