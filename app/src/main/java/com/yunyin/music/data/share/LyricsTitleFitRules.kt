package com.yunyin.music.data.share

/**
 * Pure single-line title fitting for the lyric card.
 *
 * The design size is tried first; if the measured text exceeds the content width the size steps down in 2 px
 * increments until it fits or reaches [MIN_SIZE]. Below the floor the caller ellipsises rather than wrapping —
 * the card's title must stay exactly one line so the lyric block's vertical centring has a stable header height.
 */
object LyricsTitleFitRules {

    const val MIN_SIZE = 44f
    const val STEP = 2f

    fun fits(text: String, maxWidth: Float, size: Float, measure: (String, Float) -> Float): Boolean =
        measure(text, size) <= maxWidth

    fun fitSize(
        text: String,
        maxWidth: Float,
        baseSize: Float,
        minSize: Float = MIN_SIZE,
        measure: (String, Float) -> Float,
    ): Float {
        var size = baseSize
        while (size > minSize && !fits(text, maxWidth, size, measure)) {
            size = (size - STEP).coerceAtLeast(minSize)
        }
        return size
    }

    /** True when even the minimum size cannot fit; the caller must ellipsise. */
    fun needsEllipsis(
        text: String,
        maxWidth: Float,
        minSize: Float = MIN_SIZE,
        measure: (String, Float) -> Float,
    ): Boolean = !fits(text, maxWidth, minSize, measure)
}
