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
    fun `tablet breakpoint columns and tabletop decision are pinned`() {
        assertFalse(WindowLayoutRules.isTablet(839f))
        assertTrue(WindowLayoutRules.isTablet(840f))
        assertEquals(1, WindowLayoutRules.cardColumns(600f))
        assertEquals(2, WindowLayoutRules.cardColumns(1200f))
        assertTrue(WindowLayoutRules.isTabletop(true))
        assertFalse(WindowLayoutRules.isTabletop(false))
        assertEquals(30f, WindowLayoutRules.hingeGutterDp(90, true, 3f), 0f)
        assertEquals(0f, WindowLayoutRules.hingeGutterDp(90, false, 3f), 0f)
    }
}
