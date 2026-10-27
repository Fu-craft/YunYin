// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

/**
 * Runs [sources] in order and returns the first hit.
 *
 * Order matters: the operator-configured endpoint answers fast and is already proven on this build,
 * so it goes first; Apple's own catalogue is the fallback for albums that endpoint does not carry
 * (notably Chinese releases, which only appear in the `cn` storefront).
 */
class ChainedArtworkSource(private val sources: List<AnimatedArtworkSource>) : AnimatedArtworkSource {
    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
        for (source in sources) {
            val hit = source.search(artist, album, title)
            if (hit != null && hit.url.isNotBlank()) return hit
        }
        return null
    }
}

/**
 * Narrows a master-playlist URL to a single AVC rendition before it reaches the player.
 *
 * Done in the lookup layer rather than in the player so the resolved URL is what gets cached: the
 * same album reuses the rendition without another playlist fetch.
 */
class VariantResolvingSource(
    private val inner: AnimatedArtworkSource,
    private val resolver: HlsVariantResolver,
) : AnimatedArtworkSource {
    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
        val hit = inner.search(artist, album, title) ?: return null
        if (hit.url.isBlank()) return null
        return hit.copy(url = resolver.resolve(hit.url))
    }
}
