// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscribeRulesTest {
    @Test
    fun `only a real session may subscribe`() {
        assertTrue(SubscribeRules.canSubscribe("MUSIC_U=x", 42L, anonymous = false))
        assertFalse(SubscribeRules.canSubscribe("MUSIC_U=x", 42L, anonymous = true))
        assertFalse(SubscribeRules.canSubscribe("", 42L, anonymous = false))
        assertFalse(SubscribeRules.canSubscribe("MUSIC_U=x", 0L, anonymous = false))
    }

    @Test
    fun `detail beats the cached list row and drives the label`() {
        assertTrue(SubscribeRules.seedSubscribed(detail = true, cache = false))
        assertFalse(SubscribeRules.seedSubscribed(detail = false, cache = true))
        assertTrue(SubscribeRules.seedSubscribed(detail = null, cache = true))
        assertEquals("取消收藏", SubscribeRules.label(true))
        assertEquals("收藏", SubscribeRules.label(false))
    }

    @Test
    fun `optimistic state is reverted on failure only`() {
        assertTrue(SubscribeRules.shouldRevert(ok = false))
        assertFalse(SubscribeRules.shouldRevert(ok = true))
        assertEquals(500L, SubscribeRules.DEBOUNCE_MS)
    }
}
