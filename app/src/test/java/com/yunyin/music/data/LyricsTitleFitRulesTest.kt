// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.share.LyricsTitleFitRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the single-line title rule: base size, gradual shrink, floor + ellipsis. */
class LyricsTitleFitRulesTest {

    // Deterministic stand-in for Paint.measureText: 0.55 em per character.
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.55f }

    @Test
    fun `a short title keeps the design size`() {
        val size = LyricsTitleFitRules.fitSize("夜曲", 600f, baseSize = 76f, measure = measure)
        assertEquals(76f, size, 0.01f)
        assertFalse(LyricsTitleFitRules.needsEllipsis("夜曲", 600f, 44f, measure))
    }

    @Test
    fun `a long title shrinks gradually but stays above the floor`() {
        val text = "your princess (nyamu / ra ver.)"
        val size = LyricsTitleFitRules.fitSize(text, 800f, baseSize = 76f, measure = measure)
        assertTrue(size < 76f)
        assertTrue(size >= LyricsTitleFitRules.MIN_SIZE)
        assertTrue(measure(text, size) <= 800f)
        // One step larger would not fit.
        assertTrue(measure(text, size + 2f) > 800f)
    }

    @Test
    fun `a title too long for the floor reports ellipsis at the minimum size`() {
        val veryLong = "a".repeat(120)
        val size = LyricsTitleFitRules.fitSize(veryLong, 400f, baseSize = 76f, measure = measure)
        assertEquals(LyricsTitleFitRules.MIN_SIZE, size, 0.01f)
        assertTrue(LyricsTitleFitRules.needsEllipsis(veryLong, 400f, 44f, measure))
    }
}
