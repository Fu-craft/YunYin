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
    fun `palette field feather and pause rules are pinned`() {
        assertTrue(FullScreenCoverRules.usesOwnBackdrop(PlayerStyle.Immersive, false))
        assertFalse(FullScreenCoverRules.usesOwnBackdrop(PlayerStyle.Immersive, true))
        assertFalse(FullScreenCoverRules.backgroundStyleComposed(PlayerStyle.Immersive, false))
        assertTrue(FullScreenCoverRules.backgroundStyleComposed(PlayerStyle.Classic, false))
        assertTrue(FullScreenCoverRules.backdropIsStatic(PlayerStyle.Immersive))

        val black = FullScreenCoverRules.averageColor(IntArray(16) { 0xFF000000.toInt() })
        val white = FullScreenCoverRules.averageColor(IntArray(16) { 0xFFFFFFFF.toInt() })
        assertTrue(black != null && FullScreenCoverRules.luminance(black) < 0.1f)
        assertTrue(white != null && FullScreenCoverRules.luminance(white) > 0.9f)
        val midField = FullScreenCoverRules.fieldColors(0xFF808080.toInt())
        assertTrue(FullScreenCoverRules.luminance(midField[0]) >
            FullScreenCoverRules.luminance(midField[1]))
        assertTrue(FullScreenCoverRules.luminance(midField[1]) >
            FullScreenCoverRules.luminance(midField[2]))

        val field = FullScreenCoverRules.fieldColors(0xFF336699.toInt())
        assertTrue(FullScreenCoverRules.luminance(field[0]) >
            FullScreenCoverRules.luminance(field[1]))
        assertTrue(FullScreenCoverRules.luminance(field[1]) >
            FullScreenCoverRules.luminance(field[2]))
        assertEquals(0xFF336699.toInt(), field[1])

        assertEquals(56f, FullScreenCoverRules.FEATHER_TOP_DP, 0f)
        assertEquals(72f, FullScreenCoverRules.FEATHER_BOTTOM_DP, 0f)
        assertTrue(FullScreenCoverRules.isCenterOpaque(1000f, 2400, 1f))
        assertFalse(FullScreenCoverRules.isCenterOpaque(20f, 2400, 1f))
        assertFalse(FullScreenCoverRules.isCenterOpaque(2380f, 2400, 1f))

        assertEquals(1f, FullScreenCoverRules.pausedScale(PlayerStyle.Immersive, 0.94f), 0f)
        assertEquals(0.94f, FullScreenCoverRules.pausedScale(PlayerStyle.Classic, 0.94f), 0f)
        assertEquals(0.94f, FullScreenCoverRules.pausedScale(PlayerStyle.Vinyl, 0.94f), 0f)
    }
}
