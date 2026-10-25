// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.screens.ArtworkTapAction
import com.yunyin.music.ui.screens.ImmersiveArtworkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveArtworkRulesTest {

    @Test
    fun `immersive cover toggle and enter exit decisions are pinned`() {
        assertEquals(true, DEFAULT_NOW_PLAYING_IMMERSIVE_COVER)
        assertEquals(ArtworkTapAction.Immersive, ImmersiveArtworkRules.tapAction(true))
        assertEquals(ArtworkTapAction.Lyrics, ImmersiveArtworkRules.tapAction(false))

        assertTrue(ImmersiveArtworkRules.shouldExitOnBack(true))
        assertFalse(ImmersiveArtworkRules.shouldExitOnBack(false))
        assertTrue(ImmersiveArtworkRules.shouldExitOnLyrics(true))
        assertFalse(ImmersiveArtworkRules.shouldExitOnLyrics(false))
        assertTrue(ImmersiveArtworkRules.shouldExitOnSwipe(80f, 200L))
        assertTrue(ImmersiveArtworkRules.shouldExitOnSwipe(20f, 100L))
        assertFalse(ImmersiveArtworkRules.shouldExitOnSwipe(20f, 1000L))
        assertTrue(ImmersiveArtworkRules.shouldExitOnSwipe(64.1f, 0L, thresholdPx = 64f))
        assertFalse(ImmersiveArtworkRules.shouldExitOnSwipe(64f, 0L, thresholdPx = 64f))
    }
}
