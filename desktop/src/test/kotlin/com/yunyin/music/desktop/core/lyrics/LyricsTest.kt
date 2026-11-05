package com.yunyin.music.desktop.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsTest {

    @Test
    fun parsesDecimalPrecisions() {
        val lines = parseLrc("[00:01.5]a\n[00:02.50]b\n[00:03.500]c")
        assertEquals(listOf(1500L, 2500L, 3500L), lines.map { it.startMs })
    }

    @Test
    fun parsesMultipleStampsOnOneLine() {
        val lines = parseLrc("[00:01.00][00:10.00]repeat")
        assertEquals(2, lines.size)
        assertEquals("repeat", lines[1].content)
        assertEquals(10_000L, lines[1].startMs)
    }

    @Test
    fun translationIsConsumedOnceInOrder() {
        val lines = listOf(
            LyricLine(1_000, "a"),
            LyricLine(1_200, "b"),
            LyricLine(2_000, "c"),
        )
        val translation = listOf(
            LyricLine(1_000, "A"),
            LyricLine(2_000, "C"),
        )
        val merged = mergeTranslation(lines, translation)
        assertEquals("A", merged[0].translation)
        // 1_200 与 1_000 的译文相差 200 ms，落在容差内，但因为一对一而被拒绝。
        assertEquals(null, merged[1].translation)
        assertEquals("C", merged[2].translation)
    }

    @Test
    fun chineseOriginalWithEnglishTranslationIsSwapped() {
        assertTrue(shouldSwapMain("(痛苦)", "We came from pain"))
        val swapped = applyMainLanguage(listOf(LyricLine(0, "(痛苦)", "We came from pain")))
        assertEquals("We came from pain", swapped[0].content)
        assertEquals("(痛苦)", swapped[0].translation)
    }

    @Test
    fun chineseOnlyLineIsUntouched() {
        val lines = listOf(LyricLine(0, "晴天", "晴天"))
        assertEquals(lines, applyMainLanguage(lines))
    }

    @Test
    fun yrcStripsWordTimings() {
        val lines = parseYrc("[1000,500](1000,200,0)Hello (1200,300,0)world")
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].content)
        assertEquals(1000L, lines[0].startMs)
    }
}