// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.local.LocalArtworkRules
import com.yunyin.music.data.local.LocalMusicCollectionRules
import com.yunyin.music.data.local.LocalSong
import com.yunyin.music.data.local.LocalSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local-library browse rules: search, sort, album grouping and artist grouping.
 *
 * These are pure so the library's ordering can be pinned without a device: MediaStore returns whatever the tags say,
 * and the screen must still present albums in track order and artists in album order.
 */
class LocalMusicCollectionRulesTest {

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String,
        albumId: Long = id,
        track: Int = 0,
        year: Int = 0,
        added: Long = 0,
        duration: Long = 60_000,
    ) = LocalSong(
        id = id,
        mediaId = id,
        contentUri = "content://audio/$id",
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = duration,
        trackNumber = track,
        year = year,
        addedAtSec = added,
        artPath = null,
        displayName = "$title.mp3",
    )

    @Test
    fun `search matches title artist or album and is case-insensitive`() {
        val songs = listOf(
            song(1, "Night Drive", "Aimer", "Sun Dance"),
            song(2, "夜曲", "周杰伦", "十一月的萧邦"),
            song(3, "Other", "Millsage", "Odd Dice"),
        )
        assertEquals(listOf(1L), LocalMusicCollectionRules.search(songs, "night").map { it.id })
        assertEquals(listOf(2L), LocalMusicCollectionRules.search(songs, "周杰伦").map { it.id })
        assertEquals(listOf(3L), LocalMusicCollectionRules.search(songs, "odd").map { it.id })
        assertEquals(songs, LocalMusicCollectionRules.search(songs, "   "))
    }

    @Test
    fun `sorting covers title artist album added time and duration`() {
        val songs = listOf(
            song(1, "B-side", "Zed", "Beta", added = 10, duration = 300_000),
            song(2, "Alpha", "Amy", "Gamma", added = 30, duration = 100_000),
            song(3, "Coda", "Bob", "Alpha", added = 20, duration = 200_000),
        )
        assertEquals(listOf(2L, 1L, 3L), LocalMusicCollectionRules.sortSongs(songs, LocalSort.Title).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), LocalMusicCollectionRules.sortSongs(songs, LocalSort.Artist).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), LocalMusicCollectionRules.sortSongs(songs, LocalSort.Album).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), LocalMusicCollectionRules.sortSongs(songs, LocalSort.Added).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), LocalMusicCollectionRules.sortSongs(songs, LocalSort.Duration).map { it.id })
    }

    @Test
    fun `albums are ordered by name and songs inside an album by track number`() {
        val songs = listOf(
            song(1, "Second", "Artist A", "Album B", albumId = 20, track = 2),
            song(2, "First", "Artist A", "Album B", albumId = 20, track = 1),
            song(3, "Unknown Track", "Artist A", "Album A", albumId = 10, track = 0),
            song(4, "One", "Artist A", "Album B", albumId = 20, track = 3),
        )
        val albums = LocalMusicCollectionRules.albums(songs)
        assertEquals(listOf("Album A", "Album B"), albums.map { it.name })
        assertEquals(listOf(2L, 1L, 4L), albums[1].songs.map { it.id })
        assertEquals(3, albums[1].songs.size)
        assertEquals("Artist A", albums[1].artist)
    }

    @Test
    fun `artists order albums by year then name`() {
        val songs = listOf(
            song(1, "A1", "Singer", "Old", albumId = 1, year = 2001, track = 1),
            song(2, "B1", "Singer", "New", albumId = 2, year = 2020, track = 1),
            song(3, "C1", "Singer", "Undated", albumId = 3, year = 0, track = 1),
            song(4, "D1", "Another", "Only", albumId = 4, year = 1999, track = 1),
        )
        val artists = LocalMusicCollectionRules.artists(songs)
        assertEquals(listOf("Another", "Singer"), artists.map { it.name })
        assertEquals(listOf("Old", "New", "Undated"), artists[1].albums.map { it.name })
        assertEquals(3, artists[1].songCount)
    }

    @Test
    fun `album art fallback checks conventional folder images before the audio file name`() {
        val names = LocalArtworkRules.candidateNames("track 07.flac")
        assertEquals("cover.jpg", names.first())
        assertEquals("folder.jpg", names[3])
        assertTrue(names.contains("track 07.jpg"))
        assertTrue(names.indexOf("cover.jpg") < names.indexOf("track 07.jpg"))
        assertEquals(names, names.distinct())
    }

    @Test
    fun `local artwork travels with the Track as a file URL so the player can load it`() {
        val song = song(12, "Title", "Artist", "Album").copy(artPath = "/tmp/cover.jpg")
        val track = song.toTrack()
        assertTrue(track.coverUrl!!.startsWith("file:"))
        assertTrue(track.coverUrl!!.endsWith("cover.jpg"))
    }

    @Test
    fun `a song converts to a playable Track with a safe artist fallback`() {
        val track = song(9, "Title", "", "").toTrack()
        assertEquals("Title", track.name)
        assertEquals(listOf("未知艺人"), track.artists)
        assertEquals(9L, track.id)
    }
}
