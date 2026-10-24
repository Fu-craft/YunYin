// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New doc-literal contract: same-line skip, latest-wins coalescing, flags, id/channel constants. */
class FlymeLyricNotificationRulesTest {

    @Test
    fun `only an identical line is skipped`() {
        assertTrue(FlymeLyricNotificationRules.isSameText("line", "line"))
        assertFalse(FlymeLyricNotificationRules.isSameText("line", "next line"))
        assertFalse(FlymeLyricNotificationRules.isSameText(null, "first line"))
        assertFalse(FlymeLyricNotificationRules.isSameText("line", null))
    }

    @Test
    fun `a different line within the rate window is coalesced, not dropped`() {
        assertEquals(0L, FlymeLyricNotificationRules.nextPostDelayMs(nowMs = 10_000, lastPostMs = 0))
        assertEquals(700L, FlymeLyricNotificationRules.nextPostDelayMs(nowMs = 10_300, lastPostMs = 10_000))
        assertEquals(0L, FlymeLyricNotificationRules.nextPostDelayMs(nowMs = 11_500, lastPostMs = 10_000))
    }

    @Test
    fun `active flags are resident with both Flyme flags and stop clears them`() {
        val active = FlymeLyricNotificationRules.activeFlags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        assertTrue(active and Notification.FLAG_NO_CLEAR != 0)
        assertTrue(active and FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER != 0)
        assertTrue(active and FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER != 0)
        val cleared = FlymeLyricNotificationRules.clearedFlags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        assertTrue(cleared and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(0, cleared and FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER)
        assertEquals(0, cleared and FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER)
    }

    @Test
    fun `stable id and own channel`() {
        assertEquals(2001, FlymeLyricNotificationRules.NOTIFICATION_ID)
        assertEquals("flyme_status_bar_lyrics", FlymeLyricNotificationRules.CHANNEL_ID)
        assertEquals(1_000L, FlymeLyricNotificationRules.MIN_POST_INTERVAL_MS)
    }
}
