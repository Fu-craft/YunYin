// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.screens.FullScreenCoverRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenCoverRulesTest {

    @Test
    fun `full screen cover owns the backdrop and extends below a square cover`() {
        assertTrue(FullScreenCoverRules.usesOwnBackdrop(PlayerStyle.Immersive, false))
        assertFalse(FullScreenCoverRules.usesOwnBackdrop(PlayerStyle.Classic, false))
        assertFalse(FullScreenCoverRules.usesOwnBackdrop(PlayerStyle.Immersive, true))
        assertFalse(FullScreenCoverRules.backgroundStyleComposed(PlayerStyle.Immersive, false))
        assertTrue(FullScreenCoverRules.backgroundStyleComposed(PlayerStyle.Classic, false))

        assertEquals(1080, FullScreenCoverRules.coverHeightPx(1080))
        assertEquals(1320, FullScreenCoverRules.extensionHeightPx(2400, 1080))
        assertEquals(0, FullScreenCoverRules.extensionHeightPx(600, 1080))
        assertEquals(96, FullScreenCoverRules.blurSourceSize(1080))
        assertEquals(24, FullScreenCoverRules.blurSourceSize(100))
        assertEquals(0.35f, FullScreenCoverRules.DARKEN, 0f)
    }
}
