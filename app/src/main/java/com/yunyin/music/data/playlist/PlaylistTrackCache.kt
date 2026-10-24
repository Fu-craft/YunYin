// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.playlist

import com.yunyin.music.core.Track
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory LRU + small on-disk TTL cache for a playlist's ordered track list.
 *
 * Re-entering a playlist is the common case, and the network cost is real (a 500-track playlist is megabytes).
 * Entries older than [ttlMs] are treated as absent; a manual refresh bypasses the cache entirely (the caller passes
 * `forceRefresh`). Disk files live in the app cache dir and are best-effort: any read/write error is ignored (the
 * next load simply goes to the network), never surfaced as a user-visible failure.
 */
class PlaylistTrackCache(
    private val dir: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxMemoryEntries: Int = DEFAULT_MEMORY_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry(val savedAt: Long, val tracks: List<Track>)

    private val memory = object : LinkedHashMap<Long, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean =
            size > maxMemoryEntries
    }

    fun get(playlistId: Long): List<Track>? {
        val now = clock()
        synchronized(memory) {
            val hit = memory[playlistId]
            if (hit != null) {
                if (now - hit.savedAt in 0..ttlMs) return hit.tracks
                memory.remove(playlistId)
            }
        }
        val file = fileFor(playlistId)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val savedAt = json.optLong("savedAt")
            if (now - savedAt !in 0..ttlMs) {
                file.delete()
                return null
            }
            val arr = json.optJSONArray("tracks") ?: return null
            val tracks = (0 until arr.length()).mapNotNull { i -> decode(arr.optJSONObject(i) ?: return@mapNotNull null) }
            synchronized(memory) { memory[playlistId] = Entry(savedAt, tracks) }
            tracks
        }.getOrNull()
    }

    fun put(playlistId: Long, tracks: List<Track>) {
        val now = clock()
        synchronized(memory) { memory[playlistId] = Entry(now, tracks) }
        runCatching {
            dir.mkdirs()
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id).put("name", t.name)
                        .put("artists", JSONArray(t.artists))
                        .put("albumName", t.albumName).put("albumId", t.albumId)
                        .put("coverUrl", t.coverUrl ?: "").put("durationMs", t.durationMs)
                        .put("fee", t.fee).put("artistIds", JSONArray(t.artistIds)),
                )
            }
            val tmp = File(dir, "$playlistId.json.tmp")
            tmp.writeText(JSONObject().put("savedAt", now).put("tracks", arr).toString())
            if (!tmp.renameTo(fileFor(playlistId))) {
                fileFor(playlistId).delete()
                tmp.renameTo(fileFor(playlistId))
            }
        }
    }

    fun invalidate(playlistId: Long) {
        synchronized(memory) { memory.remove(playlistId) }
        runCatching { fileFor(playlistId).delete() }
    }

    private fun fileFor(playlistId: Long) = File(dir, "$playlistId.json")

    private fun decode(json: JSONObject): Track? {
        val id = json.optLong("id").takeIf { it > 0L } ?: return null
        val name = json.optString("name").ifBlank { return null }
        val artistsJson = json.optJSONArray("artists") ?: JSONArray()
        val artists = (0 until artistsJson.length()).mapNotNull { artistsJson.optString(it).ifBlank { null } }
        val idsJson = json.optJSONArray("artistIds") ?: JSONArray()
        val artistIds = (0 until idsJson.length()).mapNotNull { idsJson.optLong(it).takeIf { v -> v > 0L } }
        return Track(
            id = id, name = name, artists = artists,
            albumName = json.optString("albumName"), albumId = json.optLong("albumId"),
            coverUrl = json.optString("coverUrl").ifBlank { null },
            durationMs = json.optLong("durationMs"), fee = json.optInt("fee"),
            artistIds = artistIds,
        )
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_MEMORY_ENTRIES = 6
    }
}
