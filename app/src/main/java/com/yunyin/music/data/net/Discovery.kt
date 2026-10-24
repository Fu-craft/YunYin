// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import com.yunyin.music.core.AlbumInfo
import com.yunyin.music.core.Track
import org.json.JSONObject

data class ArtistSuggestion(val id: Long, val name: String, val subtitle: String)
data class AlbumSuggestion(val id: Long, val name: String, val artistName: String)

/** Search suggestions grouped as the deployment returns them (`result.songs/artists/albums`). */
data class SearchSuggestions(
    val songs: List<Track> = emptyList(),
    val artists: List<ArtistSuggestion> = emptyList(),
    val albums: List<AlbumSuggestion> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

/** Pure discovery rules: grouping caps, lyric-section trigger, suggestion visibility and paging. */
object DiscoveryRules {
    const val SUGGEST_DEBOUNCE_MS = 300L
    const val GROUP_LIMIT = 3

    fun <T> limit(items: List<T>): List<T> = items.take(GROUP_LIMIT)

    /** Suggestions belong to the typing state only; submitting or clearing hides them. */
    fun suggestionsVisible(keyword: String, searched: Boolean): Boolean =
        keyword.isNotBlank() && !searched

    /** Lyric matches are an extra section, never a replacement for normal results. */
    fun lyricSectionVisible(results: List<Track>, lyricResults: List<Track>): Boolean =
        results.isEmpty() && lyricResults.isNotEmpty()

    fun nextOffset(currentOffset: Int, pageSize: Int, more: Boolean): Int? =
        if (more) currentOffset + pageSize else null
}

/** Defensive parsers for the three discovery payloads (shapes probed on the deployed instance). */
object DiscoveryParsers {

    fun parseSuggest(json: JSONObject): SearchSuggestions {
        val result = json.optJSONObject("result") ?: return SearchSuggestions()
        return SearchSuggestions(
            songs = DiscoveryRules.limit(songList(result.optJSONArray("songs"))),
            artists = DiscoveryRules.limit(artistList(result.optJSONArray("artists"))),
            albums = DiscoveryRules.limit(albumList(result.optJSONArray("albums"))),
        )
    }

    fun hotWords(json: JSONObject): List<String> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("searchWord")?.ifBlank { null }
        }
    }

    fun lyricSongs(json: JSONObject): List<Track> =
        songList(json.optJSONObject("result")?.optJSONArray("songs"))

    private fun songList(array: org.json.JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            val name = o.optString("name")
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            val artistsArray = o.optJSONArray("artists") ?: o.optJSONArray("ar")
            val artists = (0 until (artistsArray?.length() ?: 0)).mapNotNull { j ->
                artistsArray?.optJSONObject(j)?.optString("name")?.ifBlank { null }
            }
            val album = o.optJSONObject("album") ?: o.optJSONObject("al")
            Track(
                id = id,
                name = name,
                artists = artists,
                albumName = album?.optString("name").orEmpty(),
                albumId = album?.optLong("id") ?: 0L,
                coverUrl = null,
                durationMs = o.optLong("duration").takeIf { it > 1000L } ?: o.optLong("dt"),
                fee = o.optInt("fee"),
            )
        }
    }

    private fun artistList(array: org.json.JSONArray?): List<ArtistSuggestion> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            val name = o.optString("name")
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            ArtistSuggestion(id, name, "歌手")
        }
    }

    private fun albumList(array: org.json.JSONArray?): List<AlbumSuggestion> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            val name = o.optString("name")
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            AlbumSuggestion(id, name, o.optJSONObject("artist")?.optString("name").orEmpty())
        }
    }
}
