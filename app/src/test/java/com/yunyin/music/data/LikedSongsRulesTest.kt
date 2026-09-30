package com.yunyin.music.data

import com.yunyin.music.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liked list's two pure rules: how a stored track round-trips, and how the local and cloud halves
 * combine.
 *
 * These are the parts with real requirements behind them — a guest must see their local likes, a track
 * known to both sources must not appear twice, and a locally liked song must come first — so they are
 * pinned here rather than left to be discovered by using the app.
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
        // Too few fields: a truncated write must lose that row, not break the whole list.
        assertNull(TrackText.decode("42\u0001name"))
        assertNull(TrackText.decode(""))
    }

    @Test
    fun `local likes come first and duplicates collapse`() {
        val local = listOf(track(1L), track(2L))
        // Track 2 exists in both; track 3 is cloud-only.
        val merged = mergeLiked(local, listOf(track(3L), track(2L)))

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `the local copy wins when both sources know a track`() {
        val local = track(5L, name = "本地名称")
        val cloud = track(5L, name = "云端名称")
        val merged = mergeLiked(listOf(local), listOf(cloud))

        assertEquals(1, merged.size)
        assertEquals("本地名称", merged[0].name)
    }

    @Test
    fun `a guest still sees the local list`() {
        // No cloud half at all is the guest case, and it must not empty the list.
        val merged = mergeLiked(listOf(track(9L)), emptyList())
        assertTrue(merged.isNotEmpty())
        assertEquals(listOf(9L), merged.map { it.id })
    }

    @Test
    fun `an empty local list with cloud likes still shows the cloud list`() {
        val merged = mergeLiked(emptyList(), listOf(track(4L), track(6L)))
        assertEquals(listOf(4L, 6L), merged.map { it.id })
    }
}
