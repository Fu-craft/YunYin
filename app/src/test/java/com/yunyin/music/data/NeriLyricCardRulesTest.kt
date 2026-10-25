// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.data.share.NeriLyricCardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriLyricCardRulesTest {

    @Test
    fun `card measurements match NeriPlayer and adapt to both outputs`() {
        assertEquals(88f, NeriLyricCardRules.PADDING, 0f)
        assertEquals(76f, NeriLyricCardRules.CORNER, 0f)
        assertEquals(214f, NeriLyricCardRules.BOTTOM_BAR, 0f)
        assertEquals(66f, NeriLyricCardRules.LYRIC_SIZE, 0f)
        assertEquals(44f, NeriLyricCardRules.LYRIC_MIN_SIZE, 0f)
        assertEquals(932, NeriLyricCardRules.lyricsMaxHeight(1440))
        assertEquals(1412, NeriLyricCardRules.lyricsMaxHeight(1920))
    }

    @Test
    fun `line budget counts spacing only between lines and fitting stays in range`() {
        assertEquals(3, NeriLyricCardRules.lineBudget(140, 40f, 10f))
        assertEquals(2, NeriLyricCardRules.lineBudget(139, 40f, 10f))
        assertEquals(1, NeriLyricCardRules.lineBudget(10, 40f, 16f))
        val size = NeriLyricCardRules.fitSize(listOf("短句", "另一句"), 100f) { text, _ -> if (text.length > 3) 999f else 100f }
        assertEquals(66f, size, 0f)
        val shrunk = NeriLyricCardRules.fitSize(listOf("很长".repeat(40)), 100f) { _, _ -> 999f }
        assertEquals(44f, shrunk, 0f)
    }

    @Test
    fun `translation is a second row with a separate baseline and counted in the budget`() {
        val translated = NeriLyricCardRules.splitLine("どうして 君のこと\n为什么 关于你的事")
        assertEquals("どうして 君のこと", translated.primary)
        assertEquals("为什么 关于你的事", translated.translation)
        val plain = NeriLyricCardRules.splitLine("只有原文")
        assertEquals(null, plain.translation)
        assertEquals(2, NeriLyricCardRules.renderedRows(listOf("原文\n翻译")))
        assertEquals(1, NeriLyricCardRules.renderedRows(listOf("原文")))
        val primaryBaseline = 100f
        val translationBaseline = NeriLyricCardRules.translationBaseline(
            primaryBaseline = primaryBaseline,
            primaryDescent = 12f,
            translationAscent = -14f,
        )
        assertTrue(translationBaseline > primaryBaseline + 12f)
        val height = NeriLyricCardRules.blockHeight(
            lines = listOf("原文\n翻译", "无翻译"),
            primaryLineHeight = 60f,
            translationLineHeight = 34f,
            groupSpacing = 16f,
        )
        assertEquals(178f, height, 0.01f)
        assertTrue(
            NeriLyricCardRules.fitsBudget(
                canvasHeight = 1440,
                lines = listOf("原文\n翻译", "无翻译"),
                primaryLineHeight = 60f,
                translationLineHeight = 34f,
                groupSpacing = 16f,
            ),
        )
    }

}
