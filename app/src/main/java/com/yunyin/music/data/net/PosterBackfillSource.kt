// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

/**
 * Fills in a missing still frame from the motion artwork.
 *
 * The operator endpoint answers fast but says nothing about a preview frame, and the player uses that
 * frame to cover the gap between "URL known" and "first decoded frame" (and the lyrics header uses it
 * as its thumbnail). Apple's catalogue does expose it, so a hit without one asks Apple once and merges
 * it. The result is cached like any other hit, so the second request only happens per album.
 */
class PosterBackfillSource(
    private val inner: AnimatedArtworkSource,
    private val posterSource: AnimatedArtworkSource?,
    private val diag: (String) -> Unit = {},
) : AnimatedArtworkSource {

    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
        val hit = inner.search(artist, album, title) ?: return null
        val posterProvider = posterSource ?: return hit
        if (hit.posterUrl != null) return hit
        val poster = runCatching { posterProvider.search(artist, album, title)?.posterUrl }
            .onFailure { diag("stage=animated-artwork poster error reason=${(it.message ?: "unknown").take(60)}") }
            .getOrNull()
        if (poster == null) {
            diag("stage=animated-artwork poster miss album=${album.take(32)}")
            return hit
        }
        diag("stage=animated-artwork poster filled album=${album.take(32)}")
        return hit.copy(posterUrl = poster)
    }
}
