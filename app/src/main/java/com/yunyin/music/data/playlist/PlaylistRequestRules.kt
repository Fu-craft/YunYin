// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.playlist

/**
 * Cache-busting for playlist reads.
 *
 * The NeteaseCloudMusicApi deployment caches several result endpoints (`/user/playlist`, `/playlist/detail`,
 * `/playlist/track/all`) and only refreshes its cache when the request carries a `timestamp`. Without it, a create
 * can succeed and the following refetch still returns the **old** list — exactly "创建成功但不显示".
 *
 * The rule itself lives in [com.yunyin.music.data.net.RequestCacheBust] so lyric reads (and any future cached
 * endpoint) share one implementation instead of drifting copies.
 */
object PlaylistRequestRules {

    const val TIMESTAMP_PARAM = com.yunyin.music.data.net.RequestCacheBust.TIMESTAMP_PARAM

    fun withCacheBust(query: Map<String, String>, nowMs: Long): Map<String, String> =
        com.yunyin.music.data.net.RequestCacheBust.withCacheBust(query, nowMs)

    fun isCacheBusted(query: Map<String, String>): Boolean =
        com.yunyin.music.data.net.RequestCacheBust.isCacheBusted(query)
}