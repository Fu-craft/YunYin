package com.yunyin.music.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vertical-rhythm rules for the portrait player artwork.
 *
 * The regression these guard against: the cover lived in a `weight(1f)` region while its size was
 * width-capped, so extra screen height became a dead band between the cover and the title (and above
 * the cover). The rules below are what keep the spacing proportional and the controls on-screen.
 */
class PlayerArtworkLayoutRulesTest {

    @Test
    fun `a tall phone is width limited and its extra height no longer leaks into the rhythm`() {
        // 393dp-wide phone: the cover is the width fraction, not the remaining height.
        assertEquals(353.7f, PlayerArtworkLayoutRules.artworkSize(393.dp, 465.dp).value, 0.01f)
    }

    @Test
    fun `a short screen is height limited so the controls can never be pushed off-screen`() {
        // Only 242dp is left after the title row and transport block: the cover must shrink to fit.
        assertEquals(242f, PlayerArtworkLayoutRules.artworkSize(360.dp, 242.dp).value, 0.01f)
    }

    @Test
    fun `the cover size is never negative or larger than the width fraction`() {
        assertEquals(0f, PlayerArtworkLayoutRules.artworkSize(360.dp, 0.dp).value, 0.01f)
        assertEquals(0f, PlayerArtworkLayoutRules.artworkSize(360.dp, -40.dp).value, 0.01f)
        repeat(40) { step ->
            val width = (240 + step * 8).dp
            val height = (160 + step * 12).dp
            val size = PlayerArtworkLayoutRules.artworkSize(width, height)
            assertTrue(size.value >= 0f)
            assertTrue(size.value <= width.value * PlayerArtworkLayoutRules.ARTWORK_WIDTH_FRACTION + 0.01f)
            assertTrue(size.value <= height.value + 0.01f)
        }
    }

    @Test
    fun `top inset and cover-title gap scale with height inside their clamps`() {
        // Short viewport: floors keep the rhythm from collapsing.
        assertEquals(14f, PlayerArtworkLayoutRules.topInset(480.dp).value, 0.01f)
        assertEquals(18f, PlayerArtworkLayoutRules.coverTitleGap(480.dp).value, 0.01f)
        // Typical tall phone: the proportional part is in effect.
        assertEquals(20.6f, PlayerArtworkLayoutRules.topInset(825.dp).value, 0.05f)
        assertEquals(24.75f, PlayerArtworkLayoutRules.coverTitleGap(825.dp).value, 0.05f)
        // Very tall: ceilings stop the gaps from becoming the dead band again.
        assertEquals(24f, PlayerArtworkLayoutRules.topInset(1400.dp).value, 0.01f)
        assertEquals(26f, PlayerArtworkLayoutRules.coverTitleGap(1400.dp).value, 0.01f)
    }

    @Test
    fun `the rhythm never shrinks as the screen grows`() {
        var previousTop = 0f
        var previousGap = 0f
        var height = 320
        while (height <= 1600) {
            val top = PlayerArtworkLayoutRules.topInset(height.dp).value
            val gap = PlayerArtworkLayoutRules.coverTitleGap(height.dp).value
            assertTrue(top >= previousTop)
            assertTrue(gap >= previousGap)
            previousTop = top
            previousGap = gap
            height += 40
        }
    }
}
