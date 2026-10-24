// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.net.BeatModeRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape + queue rules for 心动模式 (`/playmode/intelligence/list`).
 *
 * The success payload could not be probed without a real account, so the parser intentionally accepts
 * the documented `data.recommend` shape as well as the alternatives other module builds return. These
 * tests are built from those documented shapes: if the guess is wrong, the failure is here instead of
 * silently producing an empty queue in the UI.
 */
class BeatModeRulesTest {

    private fun track(id: Long) = Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )

    private fun song(id: Long) = JSONObject().put("id", id).put("name", "song$id")

    private fun ids(json: JSONObject): List<Long> =
        BeatModeRules.songObjects(json).map { it.optLong("id") }

    @Test
    fun `parses the documented data recommend list`() {
        val json = JSONObject().put(
            "data",
            JSONObject().put("recommend", JSONArray(listOf(song(1), song(2)))),
        )
        assertEquals(listOf(1L, 2L), ids(json))
    }

    @Test
    fun `accepts data songs and data list from other module builds`() {
        val songs = JSONObject().put("data", JSONObject().put("songs", JSONArray(listOf(song(7)))))
        val list = JSONObject().put("data", JSONObject().put("list", JSONArray(listOf(song(8)))))
        assertEquals(listOf(7L), ids(songs))
        assertEquals(listOf(8L), ids(list))
    }

    @Test
    fun `accepts a flat top level list`() {
        val json = JSONObject().put("recommend", JSONArray(listOf(song(3))))
        assertEquals(listOf(3L), ids(json))
    }

    @Test
    fun `unwraps song and track wrapper objects`() {
        val wrappedSong = JSONObject().put("song", song(4))
        val wrappedTrack = JSONObject().put("track", song(5))
        val json = JSONObject().put(
            "data",
            JSONObject().put("recommend", JSONArray(listOf(wrappedSong, wrappedTrack))),
        )
        assertEquals(listOf(4L, 5L), ids(json))
    }

    @Test
    fun `drops entries without an id and de-duplicates by id`() {
        val noId = JSONObject().put("name", "broken")
        val json = JSONObject().put(
            "data",
            JSONObject().put(
                "recommend",
                JSONArray(listOf(song(1), noId, song(1), song(2))),
            ),
        )
        assertEquals(listOf(1L, 2L), ids(json))
    }

    @Test
    fun `an empty documented list does not stop a populated alternative from being read`() {
        val json = JSONObject().put(
            "data",
            JSONObject()
                .put("recommend", JSONArray())
                .put("songs", JSONArray(listOf(song(9)))),
        )
        assertEquals(listOf(9L), ids(json))
    }

    @Test
    fun `no recognised shape yields an empty list rather than an exception`() {
        assertTrue(BeatModeRules.songObjects(JSONObject().put("data", JSONObject())).isEmpty())
        assertTrue(BeatModeRules.songObjects(JSONObject()).isEmpty())
    }

    @Test
    fun `the queue starts on the seed song even when the server omitted it`() {
        val seed = track(100)
        val queue = BeatModeRules.queueFor(listOf(track(1), track(2)), seed, seed.id)
        assertEquals(listOf(100L, 1L, 2L), queue.map { it.id })
        assertEquals(0, BeatModeRules.startIndex(queue, seed.id))
    }

    @Test
    fun `the queue keeps the recommended order and de-duplicates`() {
        val queue = BeatModeRules.queueFor(listOf(track(2), track(1), track(2)), track(1), 1L)
        assertEquals(listOf(2L, 1L), queue.map { it.id })
        assertEquals(1, BeatModeRules.startIndex(queue, 1L))
    }

    @Test
    fun `an empty recommendation never becomes an empty success queue`() {
        assertTrue(BeatModeRules.queueFor(emptyList(), track(1), 1L).isEmpty())
    }

    @Test
    fun `playlist id prefers the caller, then the queue origin, then the liked playlist`() {
        assertEquals(9L, BeatModeRules.resolvePlaylistId(9L, 8L, 7L))
        assertEquals(8L, BeatModeRules.resolvePlaylistId(null, 8L, 7L))
        assertEquals(7L, BeatModeRules.resolvePlaylistId(0L, null, 7L))
        assertNull(BeatModeRules.resolvePlaylistId(null, 0L, null))
    }

    @Test
    fun `a need-login answer maps to 请先登录 and an expired session to its own message`() {
        // The deployed instance answers the need-login case with `HTTP 301 ... msg "需要登录"`.
        assertEquals(BeatModeRules.GUEST_MESSAGE, BeatModeRules.loginFailureMessage("需要登录"))
        assertEquals(BeatModeRules.GUEST_MESSAGE, BeatModeRules.loginFailureMessage("HTTP 301"))
        assertEquals(BeatModeRules.EXPIRED_MESSAGE, BeatModeRules.loginFailureMessage("HTTP 401"))
        assertEquals(BeatModeRules.EXPIRED_MESSAGE, BeatModeRules.loginFailureMessage("登录已过期"))
        // Anything else is the server's own message and must be reported unchanged, not swallowed.
        assertNull(BeatModeRules.loginFailureMessage("接口不存在"))
        assertNull(BeatModeRules.loginFailureMessage(null))
    }

    // ---------------------------------------------------------------- id extraction + hydration
    //
    // The beat-mode payload's song objects do not use /song/detail's field names, so the payload is only an
    // id source and the rows are hydrated by id. These are the tests that make the reported "plays but shows no
    // cover/title/artist" failure impossible to reintroduce silently.

    @Test
    fun `ids are extracted in order, de-duplicated and non-positive values dropped`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put(
                "recommend",
                JSONArray(
                    listOf(
                        JSONObject().put("id", 3L),
                        JSONObject().put("id", 1L),
                        JSONObject().put("id", 3L),
                        JSONObject().put("id", 0L),
                        JSONObject().put("id", -2L),
                    ),
                ),
            ),
        )
        assertEquals(listOf(3L, 1L), BeatModeRules.songIds(payload))
    }

    @Test
    fun `hydration rebuilds the queue in the original id order with full metadata`() {
        val ids = listOf(3L, 1L, 2L)
        val byId = mapOf(1L to track(1), 2L to track(2), 3L to track(3))
        val ready = BeatModeRules.hydrate(ids, byId) as BeatModeRules.Hydration.Ready
        assertEquals(listOf(3L, 1L, 2L), ready.tracks.map { it.id })
        // Given ids [a,b,c] and a fake detail response, the queue is [a,b,c] with real display fields.
        ready.tracks.forEach { row ->
            assertTrue(row.name.isNotBlank())
            assertTrue(row.artists.any { it.isNotBlank() })
        }
    }

    @Test
    fun `hydration drops ids the detail endpoint did not return, without reordering`() {
        val ready = BeatModeRules.hydrate(listOf(1L, 2L, 3L), mapOf(3L to track(3), 1L to track(1)))
            as BeatModeRules.Hydration.Ready
        assertEquals(listOf(1L, 3L), ready.tracks.map { it.id })
        assertEquals(listOf(2L), ready.droppedIds)
    }

    @Test
    fun `hydration drops an id-only row instead of queueing it`() {
        val blank = Track(
            id = 2L, name = "", artists = emptyList(), albumName = "", albumId = 0L,
            coverUrl = null, durationMs = 0L,
        )
        val ready = BeatModeRules.hydrate(listOf(1L, 2L), mapOf(1L to track(1), 2L to blank))
            as BeatModeRules.Hydration.Ready
        assertEquals(listOf(1L), ready.tracks.map { it.id })
        assertEquals(listOf(2L), ready.droppedIds)
        assertTrue(ready.tracks.all(BeatModeRules::isDisplayable))
    }

    @Test
    fun `hydration fails when every id is missing or id-only`() {
        assertEquals(BeatModeRules.Hydration.Failed, BeatModeRules.hydrate(listOf(1L, 2L), emptyMap()))
        val blank = Track(
            id = 1L, name = "", artists = listOf(""), albumName = "", albumId = 0L,
            coverUrl = null, durationMs = 0L,
        )
        assertEquals(BeatModeRules.Hydration.Failed, BeatModeRules.hydrate(listOf(1L), mapOf(1L to blank)))
        assertEquals(BeatModeRules.Hydration.Failed, BeatModeRules.hydrate(emptyList(), mapOf(1L to track(1))))
    }

    @Test
    fun `a queue never contains an id-only row`() {
        val blank = Track(
            id = 2L, name = " ", artists = listOf(" "), albumName = "", albumId = 0L,
            coverUrl = null, durationMs = 0L,
        )
        val queue = BeatModeRules.queueFor(listOf(blank, track(1)), startTrack = blank, startSongId = blank.id)
        assertEquals(listOf(1L), queue.map { it.id })
        assertTrue(queue.all(BeatModeRules::isDisplayable))
    }
}
