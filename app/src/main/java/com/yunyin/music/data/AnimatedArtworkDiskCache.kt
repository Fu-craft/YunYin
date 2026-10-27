// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import android.content.Context
import com.yunyin.music.data.net.AnimatedArtwork
import org.json.JSONObject

/**
 * Persists the album → animated-artwork mapping across launches.
 *
 * The in-memory cache only helps within one process; without this, every cold start re-runs the
 * endpoint round trip (and, on a miss there, Apple's token + search) before the cover can move.
 * Entries are keyed like the in-memory cache (`artist \u0000 album`, normalised) and expire so a
 * moved asset does not pin a dead URL forever.
 */
class AnimatedArtworkDiskCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("animated_artwork_cache", Context.MODE_PRIVATE)

    fun read(artist: String, album: String): AnimatedArtwork? {
        val key = keyOf(artist, album) ?: return null
        val raw = prefs.getString(key, null) ?: return null
        val entry = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAt = entry.optLong("savedAt", 0L)
        if (System.currentTimeMillis() - savedAt > TTL_MS) {
            prefs.edit().remove(key).apply()
            return null
        }
        val url = entry.optString("url").takeUnless { it.isBlank() } ?: return null
        return AnimatedArtwork(
            url = url,
            urlTall = entry.optString("urlTall").takeUnless { it.isBlank() },
            posterUrl = entry.optString("posterUrl").takeUnless { it.isBlank() },
            artist = artist,
            album = album,
            isCached = true,
        )
    }

    fun write(artist: String, album: String, artwork: AnimatedArtwork) {
        val key = keyOf(artist, album) ?: return
        if (artwork.url.isBlank()) return
        val entry = JSONObject()
            .put("url", artwork.url)
            .put("urlTall", artwork.urlTall.orEmpty())
            .put("posterUrl", artwork.posterUrl.orEmpty())
            .put("savedAt", System.currentTimeMillis())
        prefs.edit().putString(key, entry.toString()).apply()
    }

    /** Drops a stale entry after playback rejected the URL it pointed at. */
    fun evict(artist: String, album: String) {
        keyOf(artist, album)?.let { prefs.edit().remove(it).apply() }
    }

    private fun keyOf(artist: String, album: String): String? {
        val a = artist.trim().lowercase()
        val b = album.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return null
        return "$a\u0000$b"
    }

    private companion object {
        /** Apple's artwork assets are stable for months; a week keeps the store small and honest. */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
