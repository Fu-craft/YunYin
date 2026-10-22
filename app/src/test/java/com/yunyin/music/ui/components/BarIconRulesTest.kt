package com.yunyin.music.ui.components

import com.yunyin.music.ui.icons.SfIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the bottom bar's icon-resolution rule.
 *
 * The glass bar renders its slot row more than once (the visible panel and the invisible
 * accent-masked copy the travelling selection samples). 4.10.0 resolved the filled state from the
 * router's selected screen while the pill ran on the bar's own selection index, so the two states
 * could disagree; resolving through [BarIconRules] is what makes every pass draw the same glyph.
 * These tests fail if a refactor ever makes a slot fall back to its resting artwork while
 * highlighted, or the action slot pick up a filled mark.
 */
class BarIconRulesTest {

    private val screenSlots = listOf(PlayerTab.Home, PlayerTab.PlaylistSquare, PlayerTab.Library)

    @Test
    fun `a resting screen slot draws exactly its outline glyph`() {
        screenSlots.forEach { tab ->
            assertSame("${tab.label} resting", tab.icon, BarIconRules.iconFor(tab, highlighted = false))
        }
    }

    @Test
    fun `a highlighted screen slot draws exactly its filled glyph`() {
        assertSame(SfIcons.HouseFill, BarIconRules.iconFor(PlayerTab.Home, highlighted = true))
        assertSame(
            SfIcons.SquareGrid2x2Fill,
            BarIconRules.iconFor(PlayerTab.PlaylistSquare, highlighted = true),
        )
        assertSame(SfIcons.PersonFill, BarIconRules.iconFor(PlayerTab.Library, highlighted = true))
    }

    @Test
    fun `outline and filled artwork differ for every screen slot in the bar`() {
        screenSlots.forEach { tab ->
            assertNotEquals(tab.label, tab.icon, tab.selectedIcon)
        }
    }

    @Test
    fun `every bar slot owns a distinct glyph pair`() {
        // Six distinct vectors for three slots: no two slots share artwork, which is what a
        // duplicate-icon regression would look like.
        val artwork = screenSlots.flatMap { listOf(it.icon, it.selectedIcon) }
        assertEquals(6, artwork.distinct().size)
    }

    @Test
    fun `the beat mode action is always the outline heart`() {
        assertSame(SfIcons.Heart, BarIconRules.beatModeIcon())
        assertNotEquals(
            "the 心动模式 action must never show a filled selected mark",
            SfIcons.HeartFill,
            BarIconRules.beatModeIcon(),
        )
    }
}
