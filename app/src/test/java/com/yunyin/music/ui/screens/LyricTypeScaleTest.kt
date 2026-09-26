package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lyric type scale.
 *
 * The landscape value was wrong twice, and neither time was it visible without a device — the
 * symptom was simply "the lyrics are too small". The scale divides the lyrics-area height by a
 * reference and then clamps, and in landscape both the area and the old reference were small, so the
 * ratio fell to the floor and the clamp absorbed any tuning. These tests assert the landscape result
 * *above* the base size on a phone-sized area, so that failure cannot recur silently.
 */
class LyricTypeScaleTest {

    /** A 1080x2400 phone at density 3.0 rotated: ~360dp tall, minus system bars. */
    private val landscapeAreaDp = 306f

    /** The same device upright: 2400px / 3.0 = 800dp. */
    private val portraitAreaDp = 800f

    @Test
    fun landscapePhoneAreaIsLargerThanTheBaseSize() {
        val scale = lyricTypeScale(isLandscape = true, lyricsAreaHeightDp = landscapeAreaDp)

        // The reported bug: landscape sat at or below the floor, i.e. <= 0.55.
        assertTrue(
            "landscape scale $scale should exceed the base size on a phone-sized area",
            scale > 1f,
        )
        // 36sp base * 1.177 = ~42sp of visible lyrics.
        assertTrue("landscape scale $scale should stay within the ceiling", scale <= MAX_LANDSCAPE_LYRIC_SCALE)
    }

    @Test
    fun landscapeIsLargerThanPortrait() {
        val land = lyricTypeScale(isLandscape = true, landscapeAreaDp)
        val port = lyricTypeScale(isLandscape = false, portraitAreaDp)
        assertTrue(
            "landscape ($land) should render larger than portrait ($port) on the same device",
            land > port,
        )
    }

    @Test
    fun landscapeFloorAppliesOnATinyArea() {
        val scale = lyricTypeScale(isLandscape = true, lyricsAreaHeightDp = 1f)
        assertEquals(MIN_LYRIC_SCALE, scale, 1e-6f)
    }

    @Test
    fun landscapeCeilingAppliesOnAVeryTallArea() {
        val scale = lyricTypeScale(isLandscape = true, lyricsAreaHeightDp = 10_000f)
        assertEquals(MAX_LANDSCAPE_LYRIC_SCALE, scale, 1e-6f)
    }

    @Test
    fun portraitIsSlightlySmallerThanTheBaseSize() {
        // The requested reduction: portrait renders a little under the nominal 36sp base, and on a
        // full-height phone the ceiling is what decides it (the height term is well above it).
        val phone = lyricTypeScale(isLandscape = false, lyricsAreaHeightDp = portraitAreaDp)
        assertEquals(MAX_PORTRAIT_LYRIC_SCALE, phone, 1e-6f)
        assertTrue("portrait scale $phone should be below the base size", phone < 1f)
    }

    @Test
    fun portraitIsHeightDrivenAndCapped() {
        // Tall portrait screens sit at the portrait ceiling, never above it.
        assertEquals(MAX_PORTRAIT_LYRIC_SCALE, lyricTypeScale(false, 620f), 1e-6f)
        assertEquals(MAX_PORTRAIT_LYRIC_SCALE, lyricTypeScale(false, 1400f), 1e-6f)

        // A short portrait viewport (a landscape-shaped area rendered upright, or a small phone)
        // shrinks rather than showing two enormous lines.
        val short = lyricTypeScale(isLandscape = false, lyricsAreaHeightDp = 300f)
        assertTrue("short portrait scale $short should be below the ceiling", short < MAX_PORTRAIT_LYRIC_SCALE)
        assertTrue("short portrait scale $short should not go below the floor", short >= MIN_LYRIC_SCALE)
    }

    @Test
    fun landscapeAtTheReferenceRendersBaseSize() {
        val scale = lyricTypeScale(isLandscape = true, lyricsAreaHeightDp = LANDSCAPE_LYRICS_REFERENCE_DP)
        assertEquals(1f, scale, 1e-6f)
    }
}
