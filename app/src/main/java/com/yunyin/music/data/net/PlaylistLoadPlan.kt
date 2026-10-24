// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import com.yunyin.music.core.Track

/**
 * Page plan for loading a playlist cheaply.
 *
 * Measured on the deployed instance (1267-track playlist): `/playlist/detail` was **2.54 MB / 6.6 s** — just to read
 * `trackIds` — while `/playlist/track/all?limit=200` was 0.42 MB / 2.2 s and `limit=500` ≈ 1.05 MB / 2.6 s. The right
 * path is therefore: **one small first page** for an immediate render, then the remaining pages of
 * [PAGE]-sized requests, at most [MAX_CONCURRENT] in flight, assembled in offset order. The heavy detail call is no
 * longer on the list path at all (`trackCount` from the already-known Playlist row is the header count).
 */
object PlaylistLoadPlan {

    const val FIRST_PAGE = 200
    const val PAGE = 500
    const val MAX_CONCURRENT = 2

    /** Hard caps (same safety budget as the previous loader). */
    const val MAX_PAGES = 20
    const val MAX_TRACKS = 10_000

    data class Page(val offset: Int, val limit: Int)

    /** First-page spec, then [PAGE]-sized pages covering [totalHint] (unknown hint → just the first page). */
    fun plan(totalHint: Int): List<Page> {
        val pages = ArrayList<Page>()
        pages += Page(0, FIRST_PAGE)
        val target = totalHint.coerceAtMost(MAX_TRACKS)
        if (target <= FIRST_PAGE) return pages
        var offset = FIRST_PAGE
        while (offset < target && pages.size < MAX_PAGES) {
            pages += Page(offset, PAGE)
            offset += PAGE
        }
        return pages
    }

    /** Contiguous prefix of completed pages, so rows never appear out of order. */
    fun contiguous(pages: Map<Int, List<Track>>, plan: List<Page>): List<Track> {
        val out = ArrayList<Track>()
        for (page in plan) {
            val rows = pages[page.offset] ?: break
            out += rows
        }
        return out
    }
}

/** One page of tracks with its measured transfer size/time, so the loader can log a real breakdown. */
data class LoadedPlaylistPage(
    val offset: Int,
    val tracks: List<com.yunyin.music.core.Track>,
    val bytes: Int,
    val durationMs: Long,
)
