// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.background.ImmersiveCoverRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveCoverRulesTest {

    @Test
    fun `immersive backdrop renders only for an enabled portrait with a cover`() {
        assertTrue(ImmersiveCoverRules.shouldRender(true, false, true))
        assertFalse(ImmersiveCoverRules.shouldRender(false, false, true))
        assertFalse(ImmersiveCoverRules.shouldRender(true, true, true))
        assertFalse(ImmersiveCoverRules.shouldRender(true, false, false))
    }

    @Test
    fun `ripple constants and gating freeze when motion is unavailable`() {
        assertEquals(0.006f, ImmersiveCoverRules.RIPPLE_AMPLITUDE, 0f)
        assertEquals(0.55f, ImmersiveCoverRules.RIPPLE_SPEED, 0f)
        assertEquals(60, ImmersiveCoverRules.FRAME_RATE)
        assertEquals(false, DEFAULT_NOW_PLAYING_IMMERSIVE_COVER)
        assertTrue(ImmersiveCoverRules.shouldAnimate(true, true, true, false))
        assertFalse(ImmersiveCoverRules.shouldAnimate(true, false, true, false))
        assertFalse(ImmersiveCoverRules.shouldAnimate(true, true, false, false))
        assertFalse(ImmersiveCoverRules.shouldAnimate(true, true, true, true))
    }
}
