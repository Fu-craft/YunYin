// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricsRulesTest {

    @Test
    fun `overlay gating and thirty second stop`() {
        assertTrue(DesktopLyricsRules.shouldShow(true, true, true))
        assertFalse(DesktopLyricsRules.shouldShow(false, true, true))
        assertFalse(DesktopLyricsRules.shouldShow(true, false, true))
        assertFalse(DesktopLyricsRules.shouldShow(true, true, false))
        assertFalse(DesktopLyricsRules.shouldStop(true, 60_000L))
        assertFalse(DesktopLyricsRules.shouldStop(false, 10_000L))
        assertTrue(DesktopLyricsRules.shouldStop(false, 30_001L))
        assertFalse(DesktopLyricsRules.chromeVisible(true, false))
        assertTrue(DesktopLyricsRules.chromeVisible(true, true))
        assertTrue(DesktopLyricsRules.chromeVisible(false, false))
        assertEquals(0, DesktopLyricsRules.panelAlpha(true, false))
        assertEquals(185, DesktopLyricsRules.panelAlpha(true, true))
        assertEquals(185, DesktopLyricsRules.panelAlpha(false, false))
        assertEquals(4_000L, DesktopLyricsRules.LOCK_REVEAL_MS)
    }
}
