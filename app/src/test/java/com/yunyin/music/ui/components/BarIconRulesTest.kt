// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import com.yunyin.music.ui.icons.SfIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the bottom bar's one-glyph-per-slot contract.
 *
 * Three releases in a row (4.10.0–4.10.2) shipped a bar that substituted a *different* drawing for
 * the selected slot, so the 主页 icon looked like a different icon the moment it was not selected.
 * The fix is structural: the bar resolves exactly [PlayerTab.icon] for every slot in both states —
 * selection is the accent tint and the travelling pill, nothing else. These tests fail if a
 * selected-state artwork ever comes back into the rule.
 */
class BarIconRulesTest {

    private val screenSlots = listOf(PlayerTab.Home, PlayerTab.PlaylistSquare, PlayerTab.Library)

    @Test
    fun `a slot resolves to its outline glyph, in every state`() {
        screenSlots.forEach { tab ->
            assertSame(tab.label, tab.icon, BarIconRules.iconFor(tab))
        }
    }

    @Test
    fun `selection never substitutes a different glyph`() {
        // The rule has no selected-state input at all — the contract the screenshots broke. A
        // selected Home must still resolve the outline House, never a filled variant.
        assertSame(SfIcons.House, BarIconRules.iconFor(PlayerTab.Home))
        assertSame(SfIcons.SquareGrid2x2, BarIconRules.iconFor(PlayerTab.PlaylistSquare))
        assertSame(SfIcons.PersonOutline, BarIconRules.iconFor(PlayerTab.Library))
    }

    @Test
    fun `every bar slot owns a distinct glyph`() {
        // Three distinct vectors for three slots: no two slots share artwork.
        val artwork = screenSlots.map { BarIconRules.iconFor(it) }
        assertEquals(3, artwork.distinct().size)
    }

    @Test
    fun `the beat mode action is the outline heart in every state`() {
        assertSame(SfIcons.Heart, BarIconRules.beatModeIcon())
        // HeartFill still exists for the like buttons, but the action slot never uses it.
        assertNotEquals(SfIcons.HeartFill, BarIconRules.beatModeIcon())
    }
}
