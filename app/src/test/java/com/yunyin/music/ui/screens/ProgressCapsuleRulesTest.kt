// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the minimum drawn width that keeps the progress capsule's leading end round. */
class ProgressCapsuleRulesTest {

    @Test
    fun `a sub-thickness fill is widened to one thickness, zero stays empty`() {
        assertEquals(0f, ProgressCapsuleRules.drawnFillWidth(0f, thicknessPx = 14f, fullWidthPx = 900f), 0.01f)
        assertEquals(14f, ProgressCapsuleRules.drawnFillWidth(3f, thicknessPx = 14f, fullWidthPx = 900f), 0.01f)
        assertEquals(120f, ProgressCapsuleRules.drawnFillWidth(120f, thicknessPx = 14f, fullWidthPx = 900f), 0.01f)
        assertEquals(900f, ProgressCapsuleRules.drawnFillWidth(950f, thicknessPx = 14f, fullWidthPx = 900f), 0.01f)
    }
}
