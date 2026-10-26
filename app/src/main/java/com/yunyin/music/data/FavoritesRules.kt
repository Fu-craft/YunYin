// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

/** Pure presentation/paging decisions for 我的收藏. */
object FavoritesRules {

    enum class Segment(val label: String) {
        Artists("歌手"),
        Albums("专辑"),
        Playlists("歌单"),
    }

    fun emptyText(segment: Segment, signedIn: Boolean): String {
        if (!signedIn) return "登录后可在这里查看收藏的歌手、专辑和歌单"
        return when (segment) {
            Segment.Artists -> "还没有收藏歌手。在歌手页点「收藏」后会出现在这里。"
            Segment.Albums -> "还没有收藏专辑。在专辑页点「收藏」后会出现在这里。"
            Segment.Playlists -> "还没有收藏歌单。在歌单页点「收藏」后会出现在这里。"
        }
    }

    fun mergeDistinct(existing: List<Long>, incoming: List<Long>): List<Long> =
        existing + incoming.filterNot { it in existing }

    fun shouldRequest(loaded: Boolean, loading: Boolean, hasMore: Boolean, appendError: Boolean): Boolean =
        loaded && !loading && hasMore && !appendError
}
