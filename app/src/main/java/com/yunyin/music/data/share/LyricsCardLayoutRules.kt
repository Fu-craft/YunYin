package com.yunyin.music.data.share

/**
 * Pure geometry for the lyric share card.
 *
 * Kept out of the Canvas renderer so the two things that went wrong in the screenshot are testable:
 *  - the safe margin is never below 6 % of the canvas on any side; and
 *  - the lyric block is vertically centred in the space left between the title block and the bottom mark,
 *    instead of being top-aligned with a large empty area below.
 */
object LyricsCardLayoutRules {

    const val MARGIN_FRACTION = 0.06f
    const val MIN_MARGIN_PX = 48
    const val LYRIC_LEADING = 1.55f

    fun marginPx(width: Int): Int = kotlin.math.ceil(width * MARGIN_FRACTION.toDouble()).toInt().coerceAtLeast(MIN_MARGIN_PX)

    fun lyricLineHeight(lineSizePx: Float): Float = lineSizePx * LYRIC_LEADING

    fun lyricBlockHeight(lineCount: Int, lineSizePx: Float): Float =
        lineCount.coerceAtLeast(0) * lyricLineHeight(lineSizePx)

    /** Top of the centred lyric block; never above the title block. */
    fun lyricBlockTop(
        canvasHeight: Int,
        topAreaBottom: Float,
        bottomReserve: Float,
        blockHeight: Float,
    ): Float {
        val available = (canvasHeight - bottomReserve - topAreaBottom).coerceAtLeast(blockHeight)
        return topAreaBottom + ((available - blockHeight) / 2f).coerceAtLeast(0f)
    }

    /** Position of the currently sung line inside the selected list, or 0 when it was not selected. */
    fun currentIndexOf(selected: List<Int>, currentLine: Int): Int {
        val at = selected.indexOf(currentLine)
        return if (at >= 0) at else 0
    }
}
