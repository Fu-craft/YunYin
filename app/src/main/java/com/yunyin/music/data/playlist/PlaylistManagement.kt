// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.playlist

import com.yunyin.music.core.Playlist

/**
 * Pure rules for playlist management: request payloads, the ownership check and name validation.
 *
 * Kept separate from [com.yunyin.music.data.net.NeteaseClient] so the wire contract can be unit-tested without a
 * server: the NeteaseCloudMusicApi write endpoints take **form** fields, and a wrong field name (`id` vs `pid`,
 * `op` placement) fails on a real account. Ownership is a security-shaped rule — only the creator may edit — so it is
 * a pure function with tests rather than an `if` in three screens.
 */
object PlaylistWrites {

    /** Max NetEase accepts for a playlist name; validated before the request so the message is ours. */
    const val MAX_NAME_LENGTH = 40
    const val MAX_DESCRIPTION_LENGTH = 1_000

    /** Form fields for `POST /playlist/create`. */
    fun createForm(name: String, description: String, isPrivate: Boolean): Map<String, String> = linkedMapOf(
        "name" to name.trim(),
        "desc" to description.trim(),
        // 10 is NetEase's "private" value; 0 is public.
        "privacy" to if (isPrivate) "10" else "0",
    )

    /** Form fields for `POST /playlist/update`. */
    fun updateForm(playlistId: Long, name: String, description: String): Map<String, String> = linkedMapOf(
        "id" to playlistId.toString(),
        "name" to name.trim(),
        "desc" to description.trim(),
    )

    /** Form fields for `POST /playlist/tracks`; [op] is `add` or `del`, the id field is `pid`. */
    fun tracksForm(playlistId: Long, operation: String, trackIds: List<Long>): Map<String, String> = linkedMapOf(
        "op" to operation,
        "pid" to playlistId.toString(),
        "tracks" to trackIds.joinToString(","),
    )

    /** Form fields for `POST /playlist/delete`. */
    fun deleteForm(playlistId: Long): Map<String, String> = linkedMapOf("id" to playlistId.toString())

    /** Null when valid; otherwise the specific message to show. */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "歌单名称不能为空"
            trimmed.length > MAX_NAME_LENGTH -> "歌单名称不能超过 $MAX_NAME_LENGTH 个字符"
            else -> null
        }
    }

    fun validateDescription(description: String): String? =
        if (description.length > MAX_DESCRIPTION_LENGTH) "歌单简介不能超过 $MAX_DESCRIPTION_LENGTH 个字符" else null
}

/**
 * Only the creator may edit or delete a playlist.
 *
 * A guest (`uid <= 0`), an unknown creator (0, e.g. a home-feed entry), the built-in liked list and the reserved id 0
 * are never owned. Rendering an edit affordance for someone else's playlist would produce a confusing 401 on tap;
 * this rule is checked both when rendering and before every write.
 */
object PlaylistOwnership {

    fun canEdit(playlist: Playlist, uid: Long): Boolean =
        canEdit(
            playlistId = playlist.id,
            creatorUserId = playlist.creatorUserId,
            isLikedSongs = playlist.isLikedSongs,
            uid = uid,
        )

    fun canEdit(playlistId: Long, creatorUserId: Long, isLikedSongs: Boolean, uid: Long): Boolean =
        uid > 0L &&
            playlistId > 0L &&
            !isLikedSongs &&
            creatorUserId > 0L &&
            creatorUserId == uid

    /** Message for a write attempted without a real session. */
    fun loginMessage(uid: Long): String =
        if (uid <= 0L) "未登录，请先登录后再管理歌单" else "登录已过期，请重新登录"
}
