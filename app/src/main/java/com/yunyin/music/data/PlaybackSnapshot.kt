// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cold-start playback snapshot: the queue, which item was playing, and how far into it.
 *
 * Restored **paused** — reopening the app should put you back where you were, not start blasting
 * audio. Only the fields needed to render the queue and re-resolve a stream are stored; the audio
 * itself is resolved again on restore.
 */
data class PlaybackSnapshot(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
) {

    fun encode(): String {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("id", track.id)
                    .put("name", track.name)
                    .put("artists", JSONArray(track.artists))
                    .put("albumName", track.albumName)
                    .put("albumId", track.albumId)
                    .put("coverUrl", track.coverUrl.orEmpty())
                    .put("durationMs", track.durationMs)
                    .put("fee", track.fee),
            )
        }
        return JSONObject()
            .put("tracks", array)
            .put("index", index)
            .put("positionMs", positionMs)
            .toString()
    }

    companion object {
        fun decode(raw: String?): PlaybackSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                val array = root.optJSONArray("tracks") ?: return null
                val tracks = (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val names = item.optJSONArray("artists") ?: JSONArray()
                    Track(
                        id = item.optLong("id"),
                        name = item.optString("name"),
                        artists = (0 until names.length()).map { names.optString(it) },
                        albumName = item.optString("albumName"),
                        albumId = item.optLong("albumId"),
                        coverUrl = item.optString("coverUrl").ifBlank { null },
                        durationMs = item.optLong("durationMs"),
                        fee = item.optInt("fee"),
                    )
                }
                if (tracks.isEmpty()) return null
                PlaybackSnapshot(
                    tracks = tracks,
                    index = root.optInt("index").coerceIn(0, tracks.lastIndex),
                    positionMs = root.optLong("positionMs").coerceAtLeast(0),
                )
            }.getOrNull()
        }
    }
}
