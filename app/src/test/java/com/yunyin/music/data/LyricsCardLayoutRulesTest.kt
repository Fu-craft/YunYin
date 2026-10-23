package com.yunyin.music.data

import com.yunyin.music.data.share.LyricsCardLayoutRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the share card's safe margin and centred lyric-block maths. */
class LyricsCardLayoutRulesTest {

    @Test
    fun `margins are at least six percent of the canvas`() {
        assertEquals(65, LyricsCardLayoutRules.marginPx(1080))
        assertTrue(LyricsCardLayoutRules.marginPx(1080) >= (1080 * 0.06f).toInt())
        assertTrue(LyricsCardLayoutRules.marginPx(600) >= 6 * 600 / 100)
        assertEquals(LyricsCardLayoutRules.MIN_MARGIN_PX, LyricsCardLayoutRules.marginPx(100))
    }

    @Test
    fun `lyric block is vertically centred in the remaining space`() {
        val top = LyricsCardLayoutRules.lyricBlockTop(
            canvasHeight = 1440,
            topAreaBottom = 500f,
            bottomReserve = 180f,
            blockHeight = 200f,
        )
        val above = top - 500f
        val below = (1440 - 180) - (top + 200f)
        assertEquals(above, below, 0.5f)
        assertTrue(top >= 500f)
    }

    @Test
    fun `leading is generous and the current line maps to the selected list`() {
        assertEquals(77.5f, LyricsCardLayoutRules.lyricLineHeight(50f), 0.01f)
        assertEquals(310f, LyricsCardLayoutRules.lyricBlockHeight(4, 50f), 0.01f)
        assertEquals(2, LyricsCardLayoutRules.currentIndexOf(listOf(1, 4, 7), 7))
        assertEquals(0, LyricsCardLayoutRules.currentIndexOf(listOf(1, 4), 9))
    }
}
