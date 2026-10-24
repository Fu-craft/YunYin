// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import com.yunyin.music.core.Track

/**
 * Pure batching/ordering rules for a playlist's full track list.
 *
 * The reported bug: `/playlist/track/all` was called exactly once with `limit = 200`, so a 566/1267-track playlist
 * stopped at 200. `/playlist/detail` however returns the **complete ordered** `trackIds[]` (probe: 1267 ids while its
 * embedded `tracks[]` was capped at 1000), and `/song/detail?ids=` accepts 500 ids per call — so the robust path is:
 * read the id list, fetch the objects in batches, and rebuild in id order.
 */
object PlaylistPagingRules {

    /** `/song/detail?ids=` accepted 500 ids in the deployed instance probe. */
    const val TRACK_BATCH = 500

    /** `/playlist/track/all` accepted 1000 in probe; used only for the fallback paging loop. */
    const val FALLBACK_PAGE_LIMIT = 1000

    /** Hard safety caps: a broken `more` flag must not loop forever. */
    const val MAX_PAGES = 20
    const val MAX_TRACKS = 10_000

    fun chunks(ids: List<Long>, size: Int = TRACK_BATCH): List<List<Long>> {
        if (size <= 0) return listOf(ids)
        return ids.chunked(size)
    }

    /**
     * Rebuilds the list in [ids] order, preserving duplicates (a playlist may contain the same song twice) and
     * dropping ids the server no longer returns (deleted tracks) without shifting anything else.
     */
    fun assemble(ids: List<Long>, byId: Map<Long, Track>): List<Track> = ids.mapNotNull { byId[it] }

    /** One iteration of the fallback paging loop. */
    data class PageDecision(
        val stop: Boolean,
        val capped: Boolean,
        val nextOffset: Int,
    )

    fun pageDecision(
        returned: Int,
        requested: Int,
        loaded: Int,
        pageIndex: Int,
        maxPages: Int = MAX_PAGES,
        maxTracks: Int = MAX_TRACKS,
    ): PageDecision {
        if (loaded >= maxTracks) return PageDecision(stop = true, capped = true, nextOffset = loaded)
        if (pageIndex + 1 >= maxPages) return PageDecision(stop = true, capped = true, nextOffset = loaded)
        // A short page normally means the end; keep going only while the server returned a full page.
        if (returned <= 0) return PageDecision(stop = true, capped = false, nextOffset = loaded)
        if (returned < requested) return PageDecision(stop = true, capped = false, nextOffset = loaded)
        return PageDecision(stop = false, capped = false, nextOffset = loaded)
    }
}
