package com.yunyin.music.data

import com.yunyin.music.data.local.LocalMusicRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local-music indexing rules.
 *
 * The player keys tracks by id, so a local MediaStore row gets a deterministic negative id; short files are ignored
 * because notification sounds make a music library look broken.
 */
class LocalMusicRulesTest {

    @Test
    fun `files shorter than twenty seconds are ignored`() {
        assertTrue(LocalMusicRules.isEligible(20_000L))
        assertTrue(!LocalMusicRules.isEligible(19_999L))
        assertTrue(LocalMusicRules.isEligible(240_000L))
    }

    @Test
    fun `the synthetic id is negative and deterministic`() {
        val first = LocalMusicRules.syntheticId(42L)
        val second = LocalMusicRules.syntheticId(42L)
        assertEquals(first, second)
        assertTrue(first < 0L)
        assertNotEquals(first, LocalMusicRules.syntheticId(43L))
    }

    @Test
    fun `a track takes its title, artist, album and duration from MediaStore`() {
        val track = LocalMusicRules.trackOf(
            mediaId = 7L,
            title = "夜曲",
            artist = "周杰伦",
            album = "十一月的萧邦",
            albumId = 99L,
            durationMs = 226_000L,
            displayName = "track07.flac",
        )
        assertEquals("夜曲", track?.name)
        assertEquals(listOf("周杰伦"), track?.artists)
        assertEquals("十一月的萧邦", track?.albumName)
        assertEquals(226_000L, track?.durationMs)
        assertEquals(LocalMusicRules.syntheticId(7L), track?.id)
    }

    @Test
    fun `a missing title falls back to the file name`() {
        val track = LocalMusicRules.trackOf(8L, "  ", "artist", null, 0L, 60_000L, "track08.mp3")
        assertEquals("track08", track?.name)
    }

    @Test
    fun `a file with no title and no file name is skipped`() {
        assertNull(LocalMusicRules.trackOf(9L, null, null, null, 0L, 60_000L, null))
    }

    @Test
    fun `a short file does not become a track`() {
        assertNull(LocalMusicRules.trackOf(10L, "ding", null, null, 0L, 1_000L, "ding.ogg"))
    }
}
