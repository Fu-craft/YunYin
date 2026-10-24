package com.yunyin.music.data

import com.yunyin.music.ui.background.AppleIosRules
import com.yunyin.music.ui.background.BackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pins the Apple Music iOS style key, ported constants, blur dimensions and audio gate. */
class AppleIosRulesTest {

    @Test
    fun `style key round trips and legacy keys stay stable`() {
        assertEquals(BackgroundStyle.AppleIos, BackgroundStyle.fromKey("apple_ios"))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey("yunyin"))
        assertEquals(BackgroundStyle.OkLabFlow, BackgroundStyle.fromKey("oklab"))
        assertEquals(BackgroundStyle.OkLabFlow, BackgroundStyle.fromKey("oklab_flow"))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey("unknown"))
        assertNotEquals(BackgroundStyle.default, BackgroundStyle.AppleIos)
        assertEquals("apple_ios", BackgroundStyle.AppleIos.storageKey)
    }

    @Test
    fun `ported constant table matches Lyricify renderer`() {
        assertEquals(300, AppleIosRules.MAX_ARTWORK_DIMENSION)
        assertEquals(4, AppleIosRules.BLUR_SURFACE_DOWNSAMPLE)
        assertEquals(42.5f, AppleIosRules.GAUSSIAN_SIGMA, 0f)
        assertEquals(42.5f, AppleIosRules.LYRICS_BLUR_SIGMA, 0f)
        assertEquals(80f, AppleIosRules.ORDINARY_BLUR_SIGMA, 0f)
        assertEquals(0.4f, AppleIosRules.DARK_BEHIND_LYRICS_SCRIM, 0f)
        assertEquals(1f / 3f, AppleIosRules.LIGHT_APPEARANCE_SCRIM, 0.000001f)
        assertEquals(1f, AppleIosRules.DITHER_STRENGTH, 0f)
        assertEquals(120f, AppleIosRules.LAYER_0_PERIOD_SECONDS, 0f)
        assertEquals(70f, AppleIosRules.LAYER_1_PERIOD_SECONDS, 0f)
        assertEquals(90f, AppleIosRules.LAYER_2_PERIOD_SECONDS, 0f)
        assertEquals(0.5f, AppleIosRules.LAYER_WEIGHT_0, 0f)
        assertEquals(0.25f, AppleIosRules.LAYER_WEIGHT_1, 0f)
        assertEquals(0.25f, AppleIosRules.LAYER_WEIGHT_2, 0f)
        assertEquals(0.4f, AppleIosRules.scrimAlpha(true), 0f)
        assertEquals(1f / 3f, AppleIosRules.scrimAlpha(false), 0.000001f)
    }

    @Test
    fun `blur pipeline caps the cover then shrinks four times`() {
        val cover = AppleIosRules.coverDimensions(1000, 500)
        assertEquals(300, cover[0])
        assertEquals(150, cover[1])
        val blur = AppleIosRules.blurDimensions(1000, 500)
        assertEquals(75, blur[0])
        assertEquals(38, blur[1])
        assertEquals(10.625f, AppleIosRules.blurSigmaSmall(), 0f)
        assertEquals(1, AppleIosRules.blurDimensions(1, 1)[0])
    }

    @Test
    fun `audio only modulates rotation when enabled and bounded`() {
        assertEquals(1f, AppleIosRules.audioRotationScale(false, 1f), 0f)
        assertEquals(1f, AppleIosRules.audioRotationScale(true, 0f), 0f)
        assertEquals(1.075f, AppleIosRules.audioRotationScale(true, 0.5f), 0.0001f)
        assertEquals(1.15f, AppleIosRules.audioRotationScale(true, 3f), 0.0001f)
        assertEquals(1f + 0.15f * 0.25f, AppleIosRules.audioRotationScale(true, 0.25f), 0.0001f)
    }

    @Test
    fun `cover fade is smoothstep and bounded`() {
        assertEquals(0f, AppleIosRules.coverFade(-1f), 0f)
        assertEquals(0f, AppleIosRules.coverFade(0f), 0f)
        assertEquals(0.5f, AppleIosRules.coverFade(0.5f), 0.0001f)
        assertEquals(1f, AppleIosRules.coverFade(1f), 0f)
        assertEquals(1f, AppleIosRules.coverFade(2f), 0f)
    }
}
