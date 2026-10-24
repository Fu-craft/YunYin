// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.screens.PlayerChromeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChromeRulesTest {

    @Test
    fun `remaining time uses a minus sign and zero at the end`() {
        assertEquals("-1:55", PlayerChromeRules.formatRemaining(5_000, 120_000))
        assertEquals("-0:07", PlayerChromeRules.formatRemaining(113_000, 120_000))
        assertEquals("0:00", PlayerChromeRules.formatRemaining(120_000, 120_000))
        assertEquals("0:00", PlayerChromeRules.formatRemaining(130_000, 120_000))
        assertEquals("0:00", PlayerChromeRules.formatElapsed(0))
        assertEquals("1:05", PlayerChromeRules.formatElapsed(65_000))
    }

    @Test
    fun `volume maps between 0 to max and 0 to 1 and hides when unusable`() {
        assertEquals(0f, PlayerChromeRules.volumeFraction(0, 15), 0f)
        assertEquals(0.5f, PlayerChromeRules.volumeFraction(5, 10), 0f)
        assertEquals(1f, PlayerChromeRules.volumeFraction(20, 15), 0f)
        assertEquals(0, PlayerChromeRules.volumeFor(-1f, 15))
        assertEquals(8, PlayerChromeRules.volumeFor(0.5f, 15))
        assertEquals(15, PlayerChromeRules.volumeFor(2f, 15))
        assertTrue(PlayerChromeRules.shouldShowVolume(15, false))
        assertFalse(PlayerChromeRules.shouldShowVolume(0, false))
        assertFalse(PlayerChromeRules.shouldShowVolume(15, true))
    }
}
