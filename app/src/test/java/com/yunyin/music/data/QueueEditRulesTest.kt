// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditRulesTest {

    @Test
    fun `move remove and clear protect the current item`() {
        assertTrue(QueueEditRules.canMove(4, 1, 3))
        assertFalse(QueueEditRules.canMove(4, 1, 1))
        assertFalse(QueueEditRules.canMove(4, -1, 2))
        assertFalse(QueueEditRules.canMove(4, 2, 4))
        assertTrue(QueueEditRules.canRemove(4, 0, 2))
        assertFalse(QueueEditRules.canRemove(4, 2, 2))
        assertFalse(QueueEditRules.canRemove(4, 4, 2))
        assertTrue(QueueEditRules.clearKeepsCurrent(4, 2))
        assertFalse(QueueEditRules.clearKeepsCurrent(0, 0))
        assertTrue(QueueEditRules.shuffleHint(true).isNotBlank())
        assertTrue(QueueEditRules.shuffleHint(false).isBlank())
    }
}
