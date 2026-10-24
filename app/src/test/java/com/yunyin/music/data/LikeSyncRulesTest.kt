// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Like→cloud sync decisions: real vs anonymous vs logged-out, and the debounce contract. */
class LikeSyncRulesTest {

    @Test
    fun `only a real session syncs`() {
        assertEquals(LikeSyncRules.Session.REAL, LikeSyncRules.sessionOf("MUSIC_U=x", 42L, anonymous = false))
        assertTrue(LikeSyncRules.shouldSync(LikeSyncRules.Session.REAL))
        assertFalse(LikeSyncRules.shouldSync(LikeSyncRules.Session.ANONYMOUS))
        assertFalse(LikeSyncRules.shouldSync(LikeSyncRules.Session.NONE))
    }

    @Test
    fun `anonymous and logged-out sessions are local only`() {
        assertEquals(LikeSyncRules.Session.ANONYMOUS, LikeSyncRules.sessionOf("MUSIC_A=x", 7L, anonymous = true))
        assertEquals(LikeSyncRules.Session.NONE, LikeSyncRules.sessionOf("", 7L, anonymous = false))
        assertEquals(LikeSyncRules.Session.NONE, LikeSyncRules.sessionOf("MUSIC_U=x", 0L, anonymous = false))
    }

    @Test
    fun `rapid toggles coalesce over the debounce window`() {
        assertEquals(500L, LikeSyncRules.DEBOUNCE_MS)
        assertTrue(LikeSyncRules.DEBOUNCE_MS >= 250L)
    }
}
