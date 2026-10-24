// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import com.yunyin.music.core.Comment

/**
 * Paging/dedupe rules for the read-only comment sheet.
 *
 * Comments arrive from two independent sources (hot list and paged all-comments); the same comment can appear in
 * both, and a refresh can overlap the previous page. Appending by id keeps the rendered list stable and prevents the
 * "load more" button from creating duplicates, which the sheet would otherwise show twice.
 */
object CommentPagingRules {

    /** Appends [incoming] in order, skipping ids already present. */
    fun appendUnique(existing: List<Comment>, incoming: List<Comment>): List<Comment> {
        val seen = existing.mapTo(HashSet()) { it.id }
        val out = ArrayList(existing)
        incoming.forEach { comment -> if (comment.id > 0L && seen.add(comment.id)) out += comment }
        return out
    }

    /** The offset for the next page, or null when the server says there are no more. */
    fun nextOffset(currentOffset: Int, pageSize: Int, more: Boolean): Int? =
        if (more) currentOffset + pageSize else null

    /** Never start a second page while one is in flight, and never after the end. */
    fun canLoadMore(more: Boolean, loading: Boolean): Boolean = more && !loading
}
