// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.beat

import androidx.compose.runtime.compositionLocalOf

/**
 * 心动模式 entry points.
 *
 * The host owns the network call, the queue replacement and the messages; the player's action sheet
 * and the playlist page's 更多 sheet only present the row and forward the tap. [signedIn] is known
 * without a round trip (the stored cookie), which is what lets a guest see `请先登录` immediately
 * instead of a failure after a request.
 *
 * [start] takes the playlist id the caller knows (the collection page passes its own; the player
 * sheet passes null and the host falls back to the queue's origin / the liked-songs playlist).
 */
class BeatModeActions(
    val signedIn: Boolean,
    val start: (playlistId: Long?) -> Unit,
)

val LocalBeatModeActions = compositionLocalOf<BeatModeActions?> { null }
