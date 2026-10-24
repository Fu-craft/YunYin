// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下一首播放 insert-position rules: after current, move when present, no-op when already next. */
class QueueInsertRulesTest {

    @Test
    fun `insert position is immediately after the current item`() {
        assertEquals(1, QueueInsertRules.insertIndex(currentIndex = 0, itemCount = 3))
        assertEquals(3, QueueInsertRules.insertIndex(currentIndex = 2, itemCount = 3))
        assertEquals(0, QueueInsertRules.insertIndex(currentIndex = 0, itemCount = 0))
        assertEquals(5, QueueInsertRules.insertIndex(currentIndex = 9, itemCount = 5))
    }

    @Test
    fun `an existing track is moved rather than duplicated`() {
        val queue = listOf(10L, 20L, 30L)
        assertEquals(2, QueueInsertRules.existingIndex(queue, 30L))
        assertEquals(-1, QueueInsertRules.existingIndex(queue, 40L))
    }

    @Test
    fun `an already-next track needs no move`() {
        assertTrue(QueueInsertRules.alreadyNext(existing = 1, currentIndex = 0))
        assertFalse(QueueInsertRules.alreadyNext(existing = 2, currentIndex = 0))
    }
}
