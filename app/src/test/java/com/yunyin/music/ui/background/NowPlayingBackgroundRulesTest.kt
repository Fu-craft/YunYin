package com.yunyin.music.ui.background

import com.yunyin.music.data.DEFAULT_NOW_PLAYING_AUDIO_REACTIVE
import com.yunyin.music.data.DEFAULT_NOW_PLAYING_COVER_BLUR
import com.yunyin.music.data.DEFAULT_NOW_PLAYING_DYNAMIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gating rules of the three now-playing background settings.
 *
 * These are the rules that decide whether a layer *does work*, not merely whether it is visible, so
 * they are worth pinning: a disabled dynamic background must not request frames, and audio
 * reactivity off must not read the audio taps. Defaults are NeriPlayer's own (blurred cover off,
 * audio reactive on, dynamic on), so an existing install gets the brand-new-looking background
 * without touching anything.
 */
class NowPlayingBackgroundRulesTest {

    @Test
    fun `defaults match NeriPlayer`() {
        assertFalse(DEFAULT_NOW_PLAYING_COVER_BLUR)
        assertTrue(DEFAULT_NOW_PLAYING_AUDIO_REACTIVE)
        assertTrue(DEFAULT_NOW_PLAYING_DYNAMIC)
    }

    @Test
    fun `dynamic background needs the setting, animations and no battery saver`() {
        assertTrue(NowPlayingBackgroundRules.shouldRunDynamic(true, true, false))
        assertFalse(NowPlayingBackgroundRules.shouldRunDynamic(false, true, false))
        assertFalse(NowPlayingBackgroundRules.shouldRunDynamic(true, false, false))
        assertFalse(NowPlayingBackgroundRules.shouldRunDynamic(true, true, true))
    }

    @Test
    fun `audio reactivity needs its own setting on top of the same gates`() {
        assertTrue(NowPlayingBackgroundRules.shouldUseAudioReactive(true, true, false))
        assertFalse(NowPlayingBackgroundRules.shouldUseAudioReactive(false, true, false))
        assertFalse(NowPlayingBackgroundRules.shouldUseAudioReactive(true, false, false))
        assertFalse(NowPlayingBackgroundRules.shouldUseAudioReactive(true, true, true))
    }

    @Test
    fun `audio reactivity can be off while the dynamic background still runs`() {
        // Time-based drift must survive turning the audio off; only the audio taps are gated.
        assertTrue(NowPlayingBackgroundRules.shouldRunDynamic(true, true, false))
        assertFalse(NowPlayingBackgroundRules.shouldUseAudioReactive(false, true, false))
    }

    @Test
    fun `cover blur is gated only by its own setting`() {
        assertTrue(NowPlayingBackgroundRules.shouldShowCoverBlur(true))
        assertFalse(NowPlayingBackgroundRules.shouldShowCoverBlur(false))
    }

    @Test
    fun `dynamic layer opacity and cover darkening stay in range`() {
        assertTrue(NowPlayingBackgroundRules.DYNAMIC_ALPHA > 0f)
        assertTrue(NowPlayingBackgroundRules.DYNAMIC_ALPHA < 1f)
        assertEquals(0f, NowPlayingBackgroundRules.coverDarken(0f), 0f)
        assertEquals(1f, NowPlayingBackgroundRules.coverDarken(1f), 0f)
        assertEquals(0.5f, NowPlayingBackgroundRules.coverDarken(0.5f), 0f)
        assertEquals(0f, NowPlayingBackgroundRules.coverDarken(-2f), 0f)
        assertEquals(1f, NowPlayingBackgroundRules.coverDarken(2f), 0f)
    }

    @Test
    fun `cover downscale is small enough to be a cheap upload`() {
        assertTrue(NowPlayingBackgroundRules.COVER_DOWNSCALE_PX in 8..128)
    }
}
