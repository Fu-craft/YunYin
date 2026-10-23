package com.yunyin.music.data.share

/**
 * Pure rules for the lyric-card line picker.
 *
 * The user may include 1–4 lines; the chosen order is the order the lines appear in the song, not the tap order, so
 * the resulting card always reads like lyrics. The default window is the current line plus the surrounding lines,
 * clamped to [MAX_LINES] and limited to the available lyrics.
 */
object LyricSelectionRules {

    const val MAX_LINES = 4

    /** Index of the line being sung at [positionMs] (start <= position), or 0 when unknown. */
    fun currentLine(starts: List<Int>, positionMs: Long): Int {
        if (starts.isEmpty()) return 0
        val index = starts.indexOfLast { it <= positionMs }
        return if (index < 0) 0 else index
    }

    /** A centered window around [currentIndex], at most [max] lines, in ascending order. */
    fun defaultSelection(lineCount: Int, currentIndex: Int, max: Int = MAX_LINES): List<Int> {
        if (lineCount <= 0 || max <= 0) return emptyList()
        val current = currentIndex.coerceIn(0, lineCount - 1)
        val take = minOf(max, lineCount)
        val from = (current - take / 2).coerceIn(0, lineCount - take)
        return (from until from + take).toList()
    }

    /** Adds [index] if there is room, removes it if already chosen; result stays ascending. */
    fun toggle(selected: List<Int>, index: Int, lineCount: Int, max: Int = MAX_LINES): List<Int> {
        if (index !in 0 until lineCount) return selected
        if (selected.contains(index)) return selected.filterNot { it == index }
        if (selected.size >= max) return selected
        return (selected + index).sorted()
    }
}
