// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

/**
 * Cache-busting for every cached NeteaseCloudMusicApi result endpoint.
 *
 * The self-hosted API caches module results unless the request carries a `timestamp`. This is the same failure class
 * that made a new playlist invisible: a read can be served from an older cached body indefinitely. One rule object
 * (instead of a copy per endpoint) so lyric/playlist/other reads cannot drift.
 */
object RequestCacheBust {

    const val TIMESTAMP_PARAM = "timestamp"

    fun withCacheBust(query: Map<String, String>, nowMs: Long): Map<String, String> =
        LinkedHashMap(query).apply { put(TIMESTAMP_PARAM, nowMs.toString()) }

    fun isCacheBusted(query: Map<String, String>): Boolean =
        query[TIMESTAMP_PARAM]?.toLongOrNull()?.let { it > 0L } == true
}
