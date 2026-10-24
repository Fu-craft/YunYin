// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

/** Pure rules for 下一首播放 (insert immediately after the current item). */
object QueueInsertRules {

    /** Insert after the current item; clamps into a valid range (empty/short queues). */
    fun insertIndex(currentIndex: Int, itemCount: Int): Int =
        (currentIndex + 1).coerceIn(0, itemCount)

    /** The index of [trackId] if already present, else -1 (already present means "move", not duplicate). */
    fun existingIndex(queueIds: List<Long>, trackId: Long): Int = queueIds.indexOf(trackId)

    /** True when the item is already the next one, so no operation is needed. */
    fun alreadyNext(existing: Int, currentIndex: Int): Boolean = existing == currentIndex + 1
}
