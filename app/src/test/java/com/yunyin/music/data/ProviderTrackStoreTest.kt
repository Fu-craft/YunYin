// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.ProviderTrackIds
import com.yunyin.music.data.providers.ProviderTrackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The persisted provider-track identity.
 *
 * The requirement behind this store is "a track added to the queue must survive a restart": ids are what the app
 * remembers, so after a restart a provider id has to be recognisable as a provider id again instead of being
 * resolved against NetEase. These tests are therefore about *restart* behaviour — a second store instance reading
 * what the first one wrote — plus the two failure modes that must not be fatal (missing file, corrupt file).
 */
class ProviderTrackStoreTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("provider-tracks").toFile()
        return File(dir, "providers/tracks.json")
    }

    private fun providerTrack(): Track {
        val id = ProviderTrackIds.idFor("tx", "0039MnYb0qxYhV")
        return Track(
            id = id,
            name = "晴天",
            artists = listOf("周杰伦"),
            albumName = "叶惠美",
            albumId = 0L,
            coverUrl = "https://y.gtimg.cn/x.jpg",
            durationMs = 269_000L,
            providerRef = ProviderRef(
                provider = "tx",
                providerId = "0039MnYb0qxYhV",
                songId = "97773",
                mediaMid = "003Qui1q2u1Zho",
                albumMid = "000MkMni19ClKG",
                intervalSeconds = 269,
                qualities = listOf("128k", "320k", "flac"),
                typeSizes = mapOf("128k" to "4.10 MiB", "320k" to "10.29 MiB", "flac" to "52.83 MiB"),
            ),
        )
    }

    @Test
    fun `a provider track is recoverable by id after a restart`() {
        val file = tempFile()
        val track = providerTrack()
        ProviderTrackStore(file).remember(track)

        // A fresh instance is what a restarted process has.
        val restored = ProviderTrackStore(file).find(track.id)
        assertNotNull(restored)
        assertEquals("晴天", restored!!.name)
        assertEquals(listOf("周杰伦"), restored.artists)
        assertEquals(269_000L, restored.durationMs)
        assertEquals("tx", restored.providerRef?.provider)
        assertEquals("0039MnYb0qxYhV", restored.providerRef?.providerId)
        assertEquals("003Qui1q2u1Zho", restored.providerRef?.mediaMid)
        assertEquals("000MkMni19ClKG", restored.providerRef?.albumMid)
        assertEquals(listOf("128k", "320k", "flac"), restored.providerRef?.qualities)
        assertEquals("97773", restored.providerRef?.songId)
        assertEquals("10.29 MiB", restored.providerRef?.typeSizes?.get("320k"))
    }

    @Test
    fun `a NetEase track is not stored and its id stays unknown`() {
        val store = ProviderTrackStore(tempFile())
        store.remember(
            Track(
                id = 42L,
                name = "n",
                artists = listOf("a"),
                albumName = "b",
                albumId = 0L,
                coverUrl = null,
                durationMs = 1000L,
            ),
        )
        assertNull(store.find(42L))
    }

    @Test
    fun `a missing file is an empty store, not a failure`() {
        val file = tempFile()
        assertNull(ProviderTrackStore(file).find(ProviderTrackIds.idFor("tx", "missing")))
    }

    @Test
    fun `a corrupt file degrades to an empty store instead of crashing playback`() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("{not json")
        assertNull(ProviderTrackStore(file).find(ProviderTrackIds.idFor("tx", "x")))
        // ...and the store can still be written to afterwards.
        val track = providerTrack()
        ProviderTrackStore(file).remember(track)
        assertNotNull(ProviderTrackStore(file).find(track.id))
    }

    @Test
    fun `remembering the same id twice replaces rather than duplicates`() {
        val file = tempFile()
        val track = providerTrack()
        ProviderTrackStore(file).remember(track)
        ProviderTrackStore(file).remember(track.copy(name = "晴天 (remastered)"))
        assertEquals("晴天 (remastered)", ProviderTrackStore(file).find(track.id)?.name)
    }
}
