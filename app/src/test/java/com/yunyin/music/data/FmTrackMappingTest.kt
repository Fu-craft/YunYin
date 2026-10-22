package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `/personal_fm` → [com.yunyin.music.core.Track] mapping for the fields the real response carries:
 * `artists[].name`, `album.picUrl`, `album.name`/`id`, `duration` and `fee`.
 */
class FmTrackMappingTest {

    @Test
    fun `personal_fm artist album cover and duration survive into the model`() {
        val track = FmTrackMapping.toTrack(
            FmTrackMapping.Fields(
                id = 3_446_954_477,
                name = "Odd Dice",
                artists = listOf("millsage"),
                albumName = "Odd Dice",
                albumId = 403_443_890,
                coverUrl = "http://p1.music.126.net/6xwcAEs9BIHsjWwOSqGeJA==/109951174097038152.jpg",
                durationMs = 248_828,
                fee = 8,
            ),
        )
        assertEquals("millsage", track.artistLine)
        assertEquals("Odd Dice", track.albumName)
        assertEquals(403_443_890L, track.albumId)
        assertEquals("http://p1.music.126.net/6xwcAEs9BIHsjWwOSqGeJA==/109951174097038152.jpg", track.coverUrl)
        assertEquals(248_828L, track.durationMs)
        assertEquals(8, track.fee)
    }

    @Test
    fun `multiple artists are joined in response order`() {
        val track = FmTrackMapping.toTrack(
            FmTrackMapping.Fields(1, "Song", listOf("A", "B"), "Album", 2, null, 1000, 0),
        )
        assertEquals(listOf("A", "B"), track.artists)
        assertEquals("A / B", track.artistLine)
    }

    @Test
    fun `a response without album art stays nullable instead of inventing a url`() {
        val track = FmTrackMapping.toTrack(
            FmTrackMapping.Fields(1, "Song", emptyList(), "", 0, null, 0, 0),
        )
        assertNull(track.coverUrl)
        assertEquals("", track.artistLine)
    }
}
