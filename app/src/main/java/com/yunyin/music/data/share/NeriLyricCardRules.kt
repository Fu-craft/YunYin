// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
// Layout values ported from NeriPlayer LyricShareSheet.kt (GPL-3.0), © NeriPlayer contributors.

package com.yunyin.music.data.share

/**
 * The square NeriPlayer card's measurements, adapted to our 1080×1440 feed and 1080×1920 story
 * canvases. The card keeps NeriPlayer's 88 px padding, 76 px corner, 214 px information bar and
 * 66/44 lyric type range; only the lyric area's height grows with the canvas.
 */
object NeriLyricCardRules {

    const val WIDTH = 1080
    const val FEED_HEIGHT = 1440
    const val STORY_HEIGHT = 1920

    const val PADDING = 88f
    const val CORNER = 76f
    const val BRAND_TOP = 84f
    const val BRAND_ICON = 48f
    const val BRAND_TEXT = 42f
    const val LYRICS_TOP = 246f
    const val BOTTOM_BAR = 214f
    const val BOTTOM_GAP = 48f

    const val LYRIC_SIZE = 66f
    const val LYRIC_MIN_SIZE = 44f
    const val LYRIC_STEP = 4f
    const val LYRIC_SPACING = 16f
    const val LYRIC_SPACING_MIN_SIZE = 12f
    const val TRANSLATION_SIZE = 34f
    const val TRANSLATION_GAP = 8f
    const val TRANSLATION_ALPHA = 205

    const val TITLE_SIZE = 46f
    const val ARTIST_SIZE = 38f
    const val COVER_SIZE = 132f
    const val COVER_CORNER = 28f

    fun height(story: Boolean): Int = if (story) STORY_HEIGHT else FEED_HEIGHT

    fun lyricsMaxHeight(canvasHeight: Int): Int =
        (canvasHeight - BOTTOM_BAR - LYRICS_TOP - BOTTOM_GAP).toInt().coerceAtLeast(1)

    fun lineSpacing(textSize: Float): Float =
        if (textSize > LYRIC_MIN_SIZE) LYRIC_SPACING else LYRIC_SPACING_MIN_SIZE

    /** The selected string is `primary\ntranslation` from LyricClipboard; split before drawing. */
    data class LyricLine(val primary: String, val translation: String?)

    fun splitLine(raw: String): LyricLine {
        val newline = raw.indexOf('\n')
        if (newline < 0) return LyricLine(raw.trim(), null)
        val primary = raw.substring(0, newline).trim()
        val translation = raw.substring(newline + 1).trim().ifBlank { null }
        return LyricLine(primary, translation)
    }

    /** Translation baselines are derived independently; this is the testable non-overlap rule. */
    fun translationBaseline(
        primaryBaseline: Float,
        primaryDescent: Float,
        translationAscent: Float,
    ): Float = primaryBaseline + primaryDescent + TRANSLATION_GAP - translationAscent

    /** Total block height including one extra row for every translation. */
    fun blockHeight(
        lines: List<String>,
        primaryLineHeight: Float,
        translationLineHeight: Float,
        groupSpacing: Float,
    ): Float {
        if (lines.isEmpty()) return 0f
        val parsed = lines.map(::splitLine)
        val translationExtra = parsed.count { it.translation != null } *
            (TRANSLATION_GAP + translationLineHeight)
        return parsed.size * primaryLineHeight + translationExtra +
            (parsed.size - 1).coerceAtLeast(0) * groupSpacing
    }

    fun renderedRows(lines: List<String>): Int =
        lines.sumOf { if (splitLine(it).translation != null) 2 else 1 }

    fun fitsBudget(
        canvasHeight: Int,
        lines: List<String>,
        primaryLineHeight: Float,
        translationLineHeight: Float,
        groupSpacing: Float,
    ): Boolean = blockHeight(lines, primaryLineHeight, translationLineHeight, groupSpacing) <=
        lyricsMaxHeight(canvasHeight)

    /** NeriPlayer's line budget: spacing appears only between lines. */
    fun lineBudget(maxHeight: Int, lineHeight: Float, spacing: Float): Int {
        require(maxHeight > 0) { "Maximum lyric card height must be positive" }
        require(lineHeight > 0f) { "Lyric card line height must be positive" }
        require(spacing >= 0f) { "Lyric card line spacing must not be negative" }
        return ((maxHeight + spacing) / (lineHeight + spacing)).toInt().coerceAtLeast(1)
    }

    /** Largest Neri-type size in 4 px steps that keeps every selected line on one line. */
    fun fitSize(
        lines: List<String>,
        maxWidth: Float,
        measure: (String, Float) -> Float,
    ): Float {
        var size = LYRIC_SIZE
        while (size > LYRIC_MIN_SIZE) {
            if (lines.all { measure(it, size) <= maxWidth }) return size
            size -= LYRIC_STEP
        }
        return LYRIC_MIN_SIZE
    }
}
