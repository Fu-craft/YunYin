package com.yunyin.music.ui.comments

import androidx.compose.runtime.compositionLocalOf

/** Opens the read-only comment sheet for the current track; provided by the player host. */
class CommentActions(val onOpen: () -> Unit)

val LocalCommentActions = compositionLocalOf<CommentActions?> { null }
