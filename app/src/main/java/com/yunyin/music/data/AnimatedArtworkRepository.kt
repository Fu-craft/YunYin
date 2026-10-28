// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.net.AnimatedArtwork
import com.yunyin.music.data.net.AnimatedArtworkLruCache
import com.yunyin.music.data.net.AnimatedArtworkRetryableException
import com.yunyin.music.data.net.AnimatedArtworkRules
import com.yunyin.music.data.net.AnimatedArtworkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Resolves animated artwork with a normalised `artist\u0000album` key, a bounded 64-entry LRU that
 * caches misses too, and one shared in-flight request per key.
 *
 * The shared request runs in the repository's own supervisor scope. A caller leaving the player
 * therefore only cancels its own `await`; it cannot orphan or cancel the request other callers may
 * be waiting for. Completed requests are always cached (including null), and the in-flight entry is
 * removed when the request finishes. No exception escapes to the UI.
 */
class AnimatedArtworkRepository(
    private val source: AnimatedArtworkSource?,
    private val diag: (String) -> Unit = {},
    /** Optional cross-launch store; the in-memory LRU only helps within one process. */
    private val disk: AnimatedArtworkDiskCache? = null,
    /**
     * Supplies the motion artwork's still frame when the answering source has none — the operator
     * endpoint returns only the stream URL, and entries cached before this existed carry no poster.
     */
    private val posterSource: AnimatedArtworkSource? = null,
) {
    val available: Boolean = source != null

    private val cache = AnimatedArtworkLruCache(64)
    private val inFlight = mutableMapOf<String, Deferred<AnimatedArtwork?>>()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(artist: String, album: String, title: String? = null): AnimatedArtwork? {
        if (source == null || !available) return null
        val key = AnimatedArtworkRules.cacheKey(artist, album) ?: return null
        cache.get(key)?.let { hit ->
            diag("stage=animated-artwork cache hit value=${hit.value != null}")
            val enriched = withPoster(artist, album, title, hit.value)
            if (enriched !== hit.value) {
                cache.put(key, enriched)
                enriched?.let { disk?.write(artist, album, it) }
            }
            return enriched
        }
        disk?.read(artist, album)?.let { stored ->
            diag("stage=animated-artwork disk hit album=${album.take(32)}")
            val enriched = withPoster(artist, album, title, stored)
            cache.put(key, enriched)
            enriched?.let { disk?.write(artist, album, it) }
            return enriched
        }
        return coroutineScope {
            val deferred = lock.withLock {
                inFlight[key] ?: scope.async {
                    var inconclusive = false
                    val result = runCatching {
                        withTimeout(20_000L) { source.search(artist, album, title) }
                    }.onFailure { error ->
                        // A 5xx / timeout / IO failure says nothing about whether the album has
                        // artwork, so it must not be remembered as a miss.
                        inconclusive = error is AnimatedArtworkRetryableException ||
                            error is TimeoutCancellationException
                        diag("stage=animated-artwork search error reason=${(error.message ?: error.javaClass.simpleName).take(80)}")
                    }.getOrNull()
                    val enriched = withPoster(artist, album, title, result)
                    if (enriched != null || !inconclusive) cache.put(key, enriched)
                    enriched?.let { disk?.write(artist, album, it) }
                    lock.withLock { inFlight.remove(key) }
                    enriched
                }.also { inFlight[key] = it }
            }
            deferred.await()
        }
    }

    /**
     * Adds the still frame from [posterSource] when a hit — cached, on disk, or fresh — carries none.
     * The result is stored, so the second request only happens once per album.
     */
    private suspend fun withPoster(
        artist: String,
        album: String,
        title: String?,
        artwork: AnimatedArtwork?,
    ): AnimatedArtwork? {
        val provider = posterSource ?: return artwork
        if (artwork == null || artwork.posterUrl != null) return artwork
        val poster = runCatching { provider.search(artist, album, title)?.posterUrl }.getOrNull()
        if (poster == null) {
            diag("stage=animated-artwork poster miss album=${album.take(32)}")
            return artwork
        }
        diag("stage=animated-artwork poster filled album=${album.take(32)}")
        return artwork.copy(posterUrl = poster)
    }

    /**
     * Warms the next track's cover while the current one plays, so pressing next does not start with
     * a static square. Failures stay silent: a prefetch is an optimisation, never a requirement.
     */
    fun prefetch(artist: String, album: String, title: String? = null) {
        if (source == null || !available) return
        if (artist.isBlank() || album.isBlank()) return
        scope.launch {
            runCatching { resolve(artist, album, title) }
        }
    }

    /** Exposed for a deterministic unit test of the dedupe contract. */
    internal fun cached(key: String): AnimatedArtwork? = cache.get(key)?.value

    internal fun inFlightCount(): Int = inFlight.size
}
