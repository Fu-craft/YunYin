// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import java.util.Locale

/** One positive artwork result. `urlTall` is optional; a blank/missing URL means no animated art. */
data class AnimatedArtwork(
    val url: String,
    val urlTall: String?,
    val artist: String,
    val album: String,
    val isCached: Boolean,
)

/** A cache hit, including a cached negative result (value == null). */
data class AnimatedArtworkCacheHit(val value: AnimatedArtwork?)

/**
 * Pure rules for the operator-supplied animated-artwork endpoint.
 *
 * There is no default host here by design: [isAvailable] is true only for an explicitly configured
 * http(s) base. A blank value makes every query return null before any networking.
 */
object AnimatedArtworkRules {

    fun isAvailable(baseUrl: String?): Boolean {
        val value = baseUrl?.trim().orEmpty()
        return value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
    }

    fun normalise(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    /** `artist \0 album`, or null when either required field is blank. */
    fun cacheKey(artist: String, album: String): String? {
        val a = normalise(artist)
        val b = normalise(album)
        if (a.isEmpty() || b.isEmpty()) return null
        return a + "\u0000" + b
    }

    fun parse(body: String?): AnimatedArtwork? = runCatching {
        val json = org.json.JSONObject(body ?: return null)
        fun text(name: String): String? = json.optString(name, "")
            .trim()
            .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
        val url = text("url") ?: return null
        AnimatedArtwork(
            url = url,
            urlTall = text("url_tall"),
            artist = text("artist") ?: "",
            album = text("album") ?: "",
            isCached = json.optBoolean("isCached", false),
        )
    }.getOrNull()

    /** The video is only shown when the feature is enabled and a resolved square HLS URL exists. */
    fun shouldShowVideo(enabled: Boolean, resolved: AnimatedArtwork?): Boolean =
        enabled && resolved != null && resolved.url.isNotBlank()
}

/** Small bounded LRU that stores both hits and negative results. */
class AnimatedArtworkLruCache(private val maxEntries: Int = 64) {
    private val entries = object : LinkedHashMap<String, AnimatedArtwork?>(maxEntries.coerceAtLeast(1), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimatedArtwork?>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): AnimatedArtworkCacheHit? =
        if (entries.containsKey(key)) AnimatedArtworkCacheHit(entries[key]) else null

    @Synchronized
    fun put(key: String, value: AnimatedArtwork?) {
        entries[key] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()
}
