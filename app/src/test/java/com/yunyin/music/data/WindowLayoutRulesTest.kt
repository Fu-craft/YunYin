// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.screens.WindowLayoutRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutRulesTest {

    @Test
    fun `two pane starts at 840dp and the hinge is split out`() {
        assertFalse(WindowLayoutRules.isTwoPane(839f))
        assertTrue(WindowLayoutRules.isTwoPane(840f))
        assertTrue(WindowLayoutRules.isTwoPane(1200f))
        assertEquals(600f, WindowLayoutRules.leftPaneWidthDp(1200f, 0f), 0f)
        assertEquals(585f, WindowLayoutRules.leftPaneWidthDp(1200f, 30f), 0f)
        assertEquals(30f, WindowLayoutRules.hingeGutterDp(90, true, 3f), 0f)
        assertEquals(0f, WindowLayoutRules.hingeGutterDp(90, false, 3f), 0f)
    }
}
