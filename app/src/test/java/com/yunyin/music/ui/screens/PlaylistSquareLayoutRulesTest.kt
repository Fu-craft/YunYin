// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import com.yunyin.music.data.net.PlaylistSquareRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单广场's pure layout rules.
 *
 * These are the decisions a screenshot cannot check: the column count a width earns, the category a
 * fresh page selects, and the card's second line. Pinning them keeps a phone grid from stretching
 * across a tablet and keeps a missing description from rendering a blank row.
 */
class PlaylistSquareLayoutRulesTest {

    @Test
    fun `phones get two columns and tablets get three`() {
        assertEquals(2, PlaylistSquareLayoutRules.columnsForWidthDp(360f))
        assertEquals(2, PlaylistSquareLayoutRules.columnsForWidthDp(411f))
        assertEquals(2, PlaylistSquareLayoutRules.columnsForWidthDp(599f))
        assertEquals(3, PlaylistSquareLayoutRules.columnsForWidthDp(600f))
        assertEquals(3, PlaylistSquareLayoutRules.columnsForWidthDp(840f))
    }

    @Test
    fun `the default category is 全部 when offered, otherwise the first one`() {
        assertEquals("全部", PlaylistSquareLayoutRules.preferredCategory(listOf("流行", "全部", "摇滚")))
        assertEquals("流行", PlaylistSquareLayoutRules.preferredCategory(listOf("流行", "摇滚")))
        assertEquals("全部", PlaylistSquareLayoutRules.preferredCategory(emptyList()))
    }

    @Test
    fun `chips always render, even before the category list arrives`() {
        assertEquals(listOf("全部"), PlaylistSquareLayoutRules.visibleCategories(emptyList()))
        assertEquals(
            listOf("全部", "流行"),
            PlaylistSquareLayoutRules.visibleCategories(listOf("全部", "流行")),
        )
    }

    @Test
    fun `card meta prefers the curator blurb then the track count`() {
        assertEquals("夜跑歌单", PlaylistSquareLayoutRules.metaLabel("  夜跑歌单 ", 20))
        assertEquals("20 首", PlaylistSquareLayoutRules.metaLabel(null, 20))
        assertEquals("20 首", PlaylistSquareLayoutRules.metaLabel("", 20))
        assertEquals("20 首", PlaylistSquareLayoutRules.metaLabel("null", 20))
        // No description and no track count: a neutral label rather than an empty line, because the
        // single meta line is what keeps the grid equal-height without a fixed-height container.
        assertEquals("歌单", PlaylistSquareLayoutRules.metaLabel(null, 0))
    }

    @Test
    fun `the meta label is never blank, so every card has exactly one meta line`() {
        listOf(
            PlaylistSquareLayoutRules.metaLabel(null, 0),
            PlaylistSquareLayoutRules.metaLabel("", 0),
            PlaylistSquareLayoutRules.metaLabel("null", 0),
            PlaylistSquareLayoutRules.metaLabel(null, 12),
            PlaylistSquareLayoutRules.metaLabel("curator", 0),
        ).forEach { assertTrue("expected a non-blank meta label, was '$it'", it.isNotBlank()) }
    }

    @Test
    fun `no play-count badge is drawn for an unknown count`() {
        assertEquals("", PlaylistSquareLayoutRules.playCountLabel(0))
        assertEquals("999", PlaylistSquareLayoutRules.playCountLabel(999))
        assertEquals("1.2万", PlaylistSquareLayoutRules.playCountLabel(12_345))
    }

    @Test
    fun `the default category is single-sourced with the data rules`() {
        assertEquals(PlaylistSquareRules.DEFAULT_CATEGORY, PlaylistSquareLayoutRules.DEFAULT_CATEGORY)
    }

    @Test
    fun `page gutters cap the grid at 900 dp on large screens`() {
        assertEquals(20f, PlaylistSquareLayoutRules.horizontalPaddingForWidthDp(360f), 0.01f)
        assertEquals(20f, PlaylistSquareLayoutRules.horizontalPaddingForWidthDp(900f), 0.01f)
        // 1200 dp tablet: (1200 - 900) / 2 = 150 dp each side, so cards never stretch past 900 dp.
        assertEquals(150f, PlaylistSquareLayoutRules.horizontalPaddingForWidthDp(1200f), 0.01f)
    }

    @Test
    fun `the chip target is 44 dp and the grid gaps sit on the 4 dp scale`() {
        assertTrue(PlaylistSquareLayoutRules.CHIP_TOUCH_HEIGHT_DP >= 44f)
        assertEquals(0f, PlaylistSquareLayoutRules.GRID_GAP_DP % 4f, 0.001f)
        assertEquals(0f, PlaylistSquareLayoutRules.GRID_VERTICAL_GAP_DP % 4f, 0.001f)
    }

    @Test
    fun `equal-height cards are a line-count contract, not a fixed height`() {
        // The card uses these as both minLines and maxLines; there is no explicit lineHeight and no
        // fixed/minimum height anywhere in the card's text chain (that model is what clipped glyphs).
        assertEquals(2, PlaylistSquareLayoutRules.TITLE_LINES)
        assertEquals(1, PlaylistSquareLayoutRules.META_LINES)
    }
}
