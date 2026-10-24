// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.share.LyricSelectionRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the lyric-card line picker: default window, ordering and the 1–4 line cap. */
class LyricSelectionRulesTest {

    @Test
    fun `current line is the last line whose start is not after the position`() {
        assertEquals(0, LyricSelectionRules.currentLine(listOf(0, 5_000, 10_000), 1_000))
        assertEquals(1, LyricSelectionRules.currentLine(listOf(0, 5_000, 10_000), 5_000))
        assertEquals(2, LyricSelectionRules.currentLine(listOf(0, 5_000, 10_000), 90_000))
        assertEquals(0, LyricSelectionRules.currentLine(emptyList(), 1_000))
    }

    @Test
    fun `default selection is a centered window of at most four lines`() {
        assertEquals(listOf(0, 1, 2, 3), LyricSelectionRules.defaultSelection(10, 0))
        assertEquals(listOf(3, 4, 5, 6), LyricSelectionRules.defaultSelection(10, 5))
        assertEquals(listOf(6, 7, 8, 9), LyricSelectionRules.defaultSelection(10, 9))
        assertEquals(listOf(0, 1), LyricSelectionRules.defaultSelection(2, 1))
        assertEquals(emptyList<Int>(), LyricSelectionRules.defaultSelection(0, 0))
    }

    @Test
    fun `toggling keeps ascending order and enforces the four-line cap`() {
        var selected = emptyList<Int>()
        selected = LyricSelectionRules.toggle(selected, 4, lineCount = 10)
        selected = LyricSelectionRules.toggle(selected, 1, lineCount = 10)
        selected = LyricSelectionRules.toggle(selected, 3, lineCount = 10)
        assertEquals(listOf(1, 3, 4), selected)
        selected = LyricSelectionRules.toggle(selected, 2, lineCount = 10)
        assertEquals(listOf(1, 2, 3, 4), selected)
        // Fifth line is refused while four are selected.
        assertEquals(listOf(1, 2, 3, 4), LyricSelectionRules.toggle(selected, 5, lineCount = 10))
        // Tapping a selected line removes it.
        assertEquals(listOf(1, 2, 4), LyricSelectionRules.toggle(selected, 3, lineCount = 10))
    }

    @Test
    fun `out-of-range line indexes are ignored`() {
        assertEquals(listOf(0), LyricSelectionRules.toggle(listOf(0), -1, lineCount = 5))
        assertEquals(listOf(0), LyricSelectionRules.toggle(listOf(0), 5, lineCount = 5))
    }
}
