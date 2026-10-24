// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the default of the new "自动进入沉浸歌词" setting (the user asked for off by default). */
class SettingsDefaultsTest {

    @Test
    fun `auto-immersive lyrics is on by default`() {
        assertTrue(
            "auto-entering immersive lyrics is the default",
            DEFAULT_AUTO_IMMERSIVE_LYRICS,
        )
    }
}
