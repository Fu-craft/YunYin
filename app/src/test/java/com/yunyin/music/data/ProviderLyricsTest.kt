package com.yunyin.music.data

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.providers.ProviderLyrics
import com.yunyin.music.data.providers.ProviderTrackIds
import com.yunyin.music.data.providers.ProviderTrackStore
import com.yunyin.music.data.providers.tx.TxLyric
import com.yunyin.music.data.providers.tx.TxSongId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The provider lyric entry point and its "never guess a track" rules.
 *
 * The network half is covered by the QRC/parser known-answer tests and verified live out of band; these tests pin
 * the decisions that must not call the network at all: which track ids this path owns, and that anything else falls
 * through to the existing lyric pipeline instead of asking the wrong platform for the wrong song.
 */
class ProviderLyricsTest {

    private fun providerTrack(provider: String = "tx"): Track {
        val mid = "0039MnYb0qxYhV"
        return Track(
            id = ProviderTrackIds.idFor(provider, mid),
            name = "晴天",
            artists = listOf("周杰伦"),
            albumName = "叶惠美",
            albumId = 0L,
            coverUrl = null,
            durationMs = 269_000L,
            providerRef = ProviderRef(
                provider = provider,
                providerId = mid,
                songId = "97773",
                mediaMid = "003Qui1q2u1Zho",
                albumMid = "000MkMni19ClKG",
                intervalSeconds = 269,
            ),
        )
    }

    private fun build(): Pair<ProviderLyrics, ProviderTrackStore> {
        val dir = Files.createTempDirectory("provider-lyrics").toFile()
        val store = ProviderTrackStore(File(dir, "tracks.json"))
        val log = LxSourceLog(File(dir, "last-error.txt"))
        return ProviderLyrics(store, TxLyric(), TxSongId(), log) to store
    }

    @Test
    fun `only stored provider tracks are owned by this path`() {
        val (lyrics, store) = build()
        val track = providerTrack()
        store.remember(track)
        assertTrue(lyrics.supports(track.id))
        assertEquals(track.name, lyrics.trackFor(track.id)?.name)
        // A NetEase id is not a provider track, and must fall through to the NetEase pipeline.
        assertFalse(lyrics.supports(42L))
        assertNull(lyrics.trackFor(42L))
    }

    @Test
    fun `a provider that has no ported lyric module is skipped without network`() = runBlocking {
        val (lyrics, store) = build()
        val kw = providerTrack(provider = "kw")
        store.remember(kw)
        assertFalse(lyrics.supports(kw.id))
        assertNull(lyrics.fetch(kw.id))
    }

    @Test
    fun `an unknown track id fetches nothing`() = runBlocking {
        val (lyrics, _) = build()
        assertNull(lyrics.fetch(ProviderTrackIds.idFor("tx", "missing")))
    }

    @Test
    fun `the numeric song id survives a restart with the provider ref`() {
        val dir = Files.createTempDirectory("provider-logic").toFile()
        val file = File(dir, "tracks.json")
        val track = providerTrack()
        ProviderTrackStore(file).remember(track)
        val restored = ProviderTrackStore(file).find(track.id)
        assertEquals("97773", restored?.providerRef?.songId)
        assertEquals("0039MnYb0qxYhV", restored?.providerRef?.providerId)
    }
}
