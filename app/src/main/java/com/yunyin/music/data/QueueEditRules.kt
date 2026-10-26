// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

/** Pure queue editing rules, shared with the sheet and PlayerController. */
object QueueEditRules {

    fun canMove(size: Int, from: Int, to: Int): Boolean =
        from in 0 until size && to in 0 until size && from != to

    fun canRemove(size: Int, index: Int, currentIndex: Int): Boolean =
        index in 0 until size && index != currentIndex

    fun clearKeepsCurrent(size: Int, currentIndex: Int): Boolean =
        size > 0 && currentIndex in 0 until size

    fun shuffleHint(enabled: Boolean): String =
        if (enabled) "随机播放中：排序作用于当前可见队列，播放顺序仍随机" else ""
}
