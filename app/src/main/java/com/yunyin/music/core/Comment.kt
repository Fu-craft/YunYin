package com.yunyin.music.core

/** A NetEase comment (read-only in this app; no posting/liking). */
data class Comment(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
    val content: String,
    val likedCount: Int,
    val timeMs: Long,
)

/** One page of the "all comments" list. */
data class CommentsPage(
    val comments: List<Comment>,
    val more: Boolean,
    val total: Int,
)
