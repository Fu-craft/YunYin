package com.yunyin.music.data

import com.yunyin.music.lyrics.composable.lyrics.LyricKaraokeMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the float/glow curves and the CJK whole-line fallback decision. */
class LyricKaraokeMotionTest {

    @Test
    fun `float is zero at rest and end, peaks at half duration`() {
        assertEquals(0f, LyricKaraokeMotion.floatFraction(0), 0f)
        assertEquals(0f, LyricKaraokeMotion.floatFraction(180), 0f)
        assertEquals(0f, LyricKaraokeMotion.floatFraction(-10), 0f)
        assertEquals(1f, LyricKaraokeMotion.floatFraction(90), 0.0001f)
        assertEquals(2f, LyricKaraokeMotion.MaxFloatDp, 0f)
        // Rise is monotonic, settle is monotonic back down.
        assertTrue(LyricKaraokeMotion.floatFraction(45) < LyricKaraokeMotion.floatFraction(90))
        assertTrue(LyricKaraokeMotion.floatFraction(135) < LyricKaraokeMotion.floatFraction(90))
        assertTrue(LyricKaraokeMotion.floatFraction(135) > 0f)
        assertTrue(LyricKaraokeMotion.shouldFloat(true))
        assertEquals(false, LyricKaraokeMotion.shouldFloat(false))
    }

    @Test
    fun `glow is zero at both ends, low peak in the middle`() {
        assertEquals(0f, LyricKaraokeMotion.glowAlpha(0f), 0f)
        assertEquals(0f, LyricKaraokeMotion.glowAlpha(1f), 0f)
        assertEquals(0.28f, LyricKaraokeMotion.glowAlpha(0.5f), 0.0001f)
        assertEquals(LyricKaraokeMotion.glowAlpha(0.4f), LyricKaraokeMotion.glowAlpha(0.6f), 0.0001f)
        assertEquals(6f, LyricKaraokeMotion.GlowRadiusDp, 0f)
    }

    @Test
    fun `CJK falls back to whole-line only when the toggle is off`() {
        assertFalse(LyricKaraokeMotion.shouldUseWholeLineHighlight("你好", true))
        assertTrue(LyricKaraokeMotion.shouldUseWholeLineHighlight("你好", false))
        assertTrue(LyricKaraokeMotion.shouldUseWholeLineHighlight("こんにちは", false))
        assertFalse(LyricKaraokeMotion.shouldUseWholeLineHighlight("Hello", false))
    }
}
