// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track

/**
 * `/personal_fm` returns the **search-shaped** song object, not the `/song/detail` shape.
 *
 * Verified against the deployed API: each `data[]` entry has `artists: [{name}]`, `album: {id, name, picUrl}`
 * and `duration`, while `ar` / `al` / `dt` are absent. The old mapper reused `mapDetailTracks`, so every field it
 * read was missing and FM rows had no cover, no artist and a zero duration even though the response carried all
 * three. Parsing into [Fields] first keeps this normalisation pure and testable.
 */
internal object FmTrackMapping {
    data class Fields(
        val id: Long,
        val name: String,
        val artists: List<String>,
        val albumName: String,
        val albumId: Long,
        val coverUrl: String?,
        val durationMs: Long,
        val fee: Int,
    )

    fun toTrack(fields: Fields): Track = Track(
        id = fields.id,
        name = fields.name,
        artists = fields.artists,
        albumName = fields.albumName,
        albumId = fields.albumId,
        coverUrl = fields.coverUrl,
        durationMs = fields.durationMs,
        fee = fields.fee,
    )
}
