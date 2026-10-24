// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.comments

import androidx.compose.runtime.compositionLocalOf

/** Opens the read-only comment sheet for the current track; provided by the player host. */
class CommentActions(val onOpen: () -> Unit)

val LocalCommentActions = compositionLocalOf<CommentActions?> { null }
