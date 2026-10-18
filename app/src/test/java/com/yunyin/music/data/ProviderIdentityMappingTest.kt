package com.yunyin.music.data

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxResolveCacheRules
import com.yunyin.music.data.lxsource.LxSupersedeRules
import com.yunyin.music.data.providers.ProviderMusicInfo
import com.yunyin.music.data.providers.ProviderTrackIds
import com.yunyin.music.data.providers.ProviderTrackStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Identity plumbing for provider tracks: search result → id → store → `musicInfo` → resolution.
 *
 * ## Why this test exists
 *
 * On the device a 周杰伦 song played with someone else's voice, and other songs failed with "Source error". Both are
 * identity failures: the audio may belong to another queue item, or a lookup may be cancelled/discarded for the
 * wrong track. This test pins that every step keeps the *same* track's identity — three provider tracks with
 * different refs must never cross over — and that the supersede rule only discards lookups for a **different** id.
 */
class ProviderIdentityMappingTest {

    private fun track(mid: String, songId: String, name: String, seconds: Int): Track = Track(
        id = ProviderTrackIds.idFor("tx", mid),
        name = name,
        artists = listOf("周杰伦"),
        albumName = "专辑",
        albumId = 0L,
        coverUrl = null,
        durationMs = seconds * 1000L,
        providerRef = ProviderRef(
            provider = "tx",
            providerId = mid,
            songId = songId,
            mediaMid = "media-$mid",
            albumMid = "album-$mid",
            intervalSeconds = seconds,
            qualities = listOf("128k", "320k", "flac"),
            typeSizes = mapOf("128k" to "4.12 MiB", "320k" to "10.29 MiB", "flac" to "52.83 MiB"),
        ),
    )

    private val tracks = listOf(
        track("0039MnYb0qxYhV", "97773", "晴天", 269),
        track("001Bbywq2gicae", "102065750", "搁浅", 240),
        track("0027oMO61wWi55", "718475", "发如雪", 299),
    )

    @Test
    fun `three provider tracks keep distinct ids, store entries and musicInfo`() {
        val dir = Files.createTempDirectory("identity").toFile()
        val store = ProviderTrackStore(File(dir, "tracks.json"))
        tracks.forEach(store::remember)

        // A fresh instance (restart) must still return each track's own ref — not a neighbour's.
        val restarted = ProviderTrackStore(File(dir, "tracks.json"))
        tracks.forEach { original ->
            val restored = restarted.find(original.id)
            assertNotNull("missing ${original.name}", restored)
            assertEquals(original.name, restored!!.name)
            assertEquals(original.providerRef?.providerId, restored.providerRef?.providerId)
            assertEquals(original.providerRef?.songId, restored.providerRef?.songId)
            assertEquals(original.providerRef?.mediaMid, restored.providerRef?.mediaMid)
        }

        // The `musicInfo` we would send must name that track and no other.
        tracks.forEach { original ->
            val info = ProviderMusicInfo.forTrack(original)
            assertEquals(original.providerRef?.providerId, info.getString("songmid"))
            assertEquals(original.providerRef?.songId?.toLong(), info.getLong("songId"))
            assertEquals(original.name, info.getString("name"))
            assertEquals(original.providerRef?.mediaMid, info.getString("strMediaMid"))
            assertFalse(info.has("hash"))
        }
    }

    @Test
    fun `cache keys and resolve keys cannot collide across these tracks`() {
        val ids = tracks.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        val keys = tracks.map { LxResolveCacheRules.key(it.id, "exhigh") }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `a thousand generated provider ids are unique and negative`() {
        val ids = (0 until 1_000).map { index -> ProviderTrackIds.idFor("tx", "mid-$index") }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it < 0L })
    }

    @Test
    fun `the same track is looked up twice identically`() {
        val first = ProviderMusicInfo.forTrack(tracks[0]).toString()
        val second = ProviderMusicInfo.forTrack(tracks[0]).toString()
        assertEquals(first, second)
        // JSON object equality is order-insensitive; compare identities explicitly to avoid a false pass.
        assertEquals(
            JSONObject(first).getString("songmid"),
            JSONObject(second).getString("songmid"),
        )
        assertNotEquals(
            ProviderMusicInfo.forTrack(tracks[0]).getString("songmid"),
            ProviderMusicInfo.forTrack(tracks[1]).getString("songmid"),
        )
    }

    @Test
    fun `a transition for the same item never discards its lookup`() {
        assertFalse(LxSupersedeRules.shouldSupersede(activeKey = 42L, currentTrackId = 42L))
        assertTrue(LxSupersedeRules.shouldSupersede(activeKey = 42L, currentTrackId = 43L))
        // A transition without an id (queue cleared) makes any in-flight lookup stale.
        assertTrue(LxSupersedeRules.shouldSupersede(activeKey = 42L, currentTrackId = null))
        // Nothing in flight: nothing to supersede.
        assertFalse(LxSupersedeRules.shouldSupersede(activeKey = null, currentTrackId = 43L))
    }

    @Test
    fun `a provider track id does not collide with a plausible NetEase id`() {
        tracks.forEach { assertTrue(it.id < 0L) }
        // NetEase ids are positive; a negative provider id can never be looked up as a NetEase id.
        assertNull(ProviderTrackStore(File(Files.createTempDirectory("empty").toFile(), "tracks.json")).find(186016L))
    }
}
