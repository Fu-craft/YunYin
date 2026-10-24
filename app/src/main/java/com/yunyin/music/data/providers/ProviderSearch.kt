// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers

import com.yunyin.music.core.Track

/** One page of merged search: NetEase results first, then results from other platforms, with a "more" flag. */
data class MusicSearchPage(
    val tracks: List<Track>,
    val hasMore: Boolean,
    /** Providers that actually contributed to this page (for the badge/log, not for filtering). */
    val providers: List<String> = emptyList(),
)

/**
 * Merge and de-duplicate search results from several catalogues.
 *
 * The app shows one list; a user searching "晴天" should not see the same song twice because NetEase and QQ both
 * have it. Two tracks are the same recording when their names normalise equal, their artist sets intersect, and
 * their lengths are within [ProviderMatcher.INTERVAL_TOLERANCE_SECONDS] — the same rule used for cross-source
 * matching, so search and playback agree about what "the same song" means.
 *
 * NetEase always comes first: it is the app's own catalogue, its rows are the ones with working artwork and
 * lyrics, and a provider duplicate adds nothing. Provider-only results are appended in provider order.
 */
internal object SearchMerge {

    fun merge(netease: List<Track>, provider: List<Track>): List<Track> {
        val merged = ArrayList<Track>(netease.size + provider.size)
        merged += netease
        provider.forEach { candidate ->
            if (merged.none { sameRecording(it, candidate) }) merged += candidate
        }
        return merged
    }

    /** Appends [incoming] to [existing], skipping anything already present (used for paging). */
    fun appendUnique(existing: List<Track>, incoming: List<Track>): List<Track> {
        val merged = ArrayList<Track>(existing.size + incoming.size)
        merged += existing
        incoming.forEach { candidate ->
            if (merged.none { it.id == candidate.id || sameRecording(it, candidate) }) merged += candidate
        }
        return merged
    }

    fun sameRecording(a: Track, b: Track): Boolean {
        if (a.id == b.id) return true
        val nameA = ProviderMatcher.normalize(a.name)
        val nameB = ProviderMatcher.normalize(b.name)
        if (nameA.isEmpty() || nameA != nameB) return false
        val singerA = ProviderMatcher.normalize(ProviderMatcher.singerKey(a.artistLine))
        val singerB = ProviderMatcher.normalize(ProviderMatcher.singerKey(b.artistLine))
        if (singerA.isNotEmpty() && singerB.isNotEmpty() && singerA != singerB) return false
        val intervalA = (a.durationMs / 1000).toInt()
        val intervalB = (b.durationMs / 1000).toInt()
        if (intervalA <= 0 || intervalB <= 0) return true
        return kotlin.math.abs(intervalA - intervalB) <= ProviderMatcher.INTERVAL_TOLERANCE_SECONDS
    }
}
