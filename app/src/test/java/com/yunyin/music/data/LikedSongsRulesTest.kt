package com.yunyin.music.data

import com.yunyin.music.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liked rules, which three views must agree on: the heart, the liked list, and its count.
 *
 * Every case here is a defect that was actually reported, or the immediate neighbour of one:
 *
 *  - the count showed only the local additions, so liking one song beside 563 cloud likes displayed "1 首";
 *  - the heart read only the local additions, so a song liked on NetEase showed an empty heart while the
 *    list showed it anyway;
 *  - un-liking a cloud-liked song had nowhere to be recorded, so the heart flipped straight back on.
 */
class LikedSongsRulesTest {

    private fun track(id: Long, name: String = "Song $id") = Track(
        id = id,
        name = name,
        artists = listOf("Artist A", "Artist B"),
        albumName = "Album",
        albumId = 7L,
        coverUrl = "https://example.invalid/$id.jpg",
        durationMs = 200_000L,
        fee = 1,
    )

    // ---------------------------------------------------------------- storage format

    @Test
    fun `a track round-trips through the stored text`() {
        val original = track(42L, "夜曲")
        val restored = TrackText.decode(TrackText.encode(original))
        assertEquals(original.id, restored?.id)
        assertEquals(original.name, restored?.name)
        assertEquals(original.artists, restored?.artists)
        assertEquals(original.coverUrl, restored?.coverUrl)
        assertEquals(original.durationMs, restored?.durationMs)
        assertEquals(original.fee, restored?.fee)
    }

    @Test
    fun `a corrupt row is skipped rather than throwing`() {
        assertNull(TrackText.decode("42\u0001name"))
        assertNull(TrackText.decode(""))
    }

    // ---------------------------------------------------------------- the rule: added ∪ (cloud − removed)

    @Test
    fun `a locally added song is liked`() {
        assertTrue(isLiked(addedIds = setOf(1L), removedIds = emptySet(), cloudIds = emptySet(), id = 1L))
    }

    @Test
    fun `a song liked only on the cloud is liked here too`() {
        // The heart used to show empty for exactly this case while the list showed the song.
        assertTrue(isLiked(addedIds = emptySet(), removedIds = emptySet(), cloudIds = setOf(563L), id = 563L))
    }

    @Test
    fun `an un-liked cloud song is not liked, even though the cloud still has it`() {
        // Nothing is written back, so the removal exists only here; this is what stops the heart bouncing.
        assertFalse(isLiked(addedIds = emptySet(), removedIds = setOf(563L), cloudIds = setOf(563L), id = 563L))
    }

    @Test
    fun `an explicit addition beats a stale removal mark`() {
        // Re-liking a song clears its removal, so added wins.
        assertTrue(isLiked(addedIds = setOf(9L), removedIds = setOf(9L), cloudIds = emptySet(), id = 9L))
    }

    @Test
    fun `an unknown song is not liked`() {
        assertFalse(isLiked(addedIds = emptySet(), removedIds = emptySet(), cloudIds = setOf(1L), id = 2L))
    }

    // ---------------------------------------------------------------- the count equals the list

    @Test
    fun `the count equals the merged list size, not the local half`() {
        val cloudIds = (100L..662L).toSet()      // 563 cloud likes
        val added = listOf(track(1L))            // one song liked in the app
        val removed = emptySet<Long>()

        val list = mergeLiked(added, cloudIds.map { track(it) }, removed)
        val count = mergedLikedCount(added, cloudIds, removed)

        assertEquals(564, count)
        assertEquals("the shown number must match the list the user opens", list.size, count)
    }

    @Test
    fun `liking an already-cloud-liked song does not inflate the count`() {
        val cloudIds = setOf(1L, 2L, 3L)
        assertEquals(3, mergedLikedCount(listOf(track(2L)), cloudIds, emptySet()))
    }

    @Test
    fun `un-liking a cloud song lowers both the list and the count`() {
        val cloudIds = setOf(1L, 2L, 3L)
        val removed = setOf(2L)

        val list = mergeLiked(emptyList(), cloudIds.map { track(it) }, removed)
        val count = mergedLikedCount(emptyList(), cloudIds, removed)

        assertEquals(listOf(1L, 3L), list.map { it.id })
        assertEquals(2, count)
        assertEquals(list.size, count)
    }

    @Test
    fun `a local addition and a local removal of the same song are not double-counted`() {
        val cloudIds = setOf(5L)
        // Liked locally, so the removal mark is irrelevant to the total.
        val added = listOf(track(5L))
        assertEquals(1, mergedLikedCount(added, cloudIds, setOf(5L)))
    }

    @Test
    fun `a guest counts only the local likes`() {
        assertEquals(2, mergedLikedCount(listOf(track(1L), track(2L)), emptySet(), emptySet()))
    }

    @Test
    fun `the local copy wins when both sources know a track`() {
        val added = track(5L, name = "本地名称")
        val cloud = track(5L, name = "云端名称")
        val merged = mergeLiked(listOf(added), listOf(cloud), emptySet())

        assertEquals(1, merged.size)
        assertEquals("本地名称", merged[0].name)
    }
}
