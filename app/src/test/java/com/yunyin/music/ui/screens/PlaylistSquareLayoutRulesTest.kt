package com.yunyin.music.ui.screens

import com.yunyin.music.data.net.PlaylistSquareRules
import org.junit.Assert.assertEquals
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
        assertEquals("", PlaylistSquareLayoutRules.metaLabel(null, 0))
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
}
