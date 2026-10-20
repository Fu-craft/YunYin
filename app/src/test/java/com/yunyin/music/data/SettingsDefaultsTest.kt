package com.yunyin.music.data

import org.junit.Assert.assertFalse
import org.junit.Test

/** Pins the default of the new "自动进入沉浸歌词" setting (the user asked for off by default). */
class SettingsDefaultsTest {

    @Test
    fun `auto-immersive lyrics is off by default`() {
        assertFalse(
            "auto-entering immersive lyrics must be opt-in",
            DEFAULT_AUTO_IMMERSIVE_LYRICS,
        )
    }
}
