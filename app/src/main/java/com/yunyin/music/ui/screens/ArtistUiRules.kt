// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import com.yunyin.music.core.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure artist-page rules: bio clamping, paging offsets and duplicate-free appends.
 *
 * The bio rule exists because a 665-char `briefDesc` must not push the song list off the screen; the paging rules
 * keep "load more" from requesting the same offset twice or appending a row twice.
 */
object ArtistUiRules {

    const val BIO_PREVIEW_CHARS = 180

    fun clampBio(text: String, maxChars: Int = BIO_PREVIEW_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars).trimEnd() + "…"
    }

    /** Null when the page has no more rows; otherwise the next offset. */
    fun nextOffset(currentOffset: Int, pageSize: Int, more: Boolean): Int? =
        if (more) currentOffset + pageSize else null

    fun appendUnique(existing: List<Track>, incoming: List<Track>): List<Track> {
        val seen = existing.mapTo(HashSet()) { it.id }
        val out = ArrayList(existing)
        incoming.forEach { track -> if (seen.add(track.id)) out += track }
        return out
    }

    fun yearOf(publishTimeMs: Long): String? {
        if (publishTimeMs <= 0L) return null
        return runCatching { SimpleDateFormat("yyyy", Locale.ROOT).format(Date(publishTimeMs)) }.getOrNull()
    }
}
