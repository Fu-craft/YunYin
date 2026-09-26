package com.yunyin.music.data

/**
 * Recognises the credit/metadata lines that lyric files carry, and strips them from the front of a
 * document before it is displayed.
 *
 * Why this exists: measured on real tracks, NetEase lyric files commonly **begin** with the song's
 * credits, and those lines have timestamps of their own. Because the whole document is what gets
 * displayed, the player highlighted `[00:00.03] 出品：网易音乐人x青云LAB` as if it were the first
 * lyric — so "lyrics" appeared the moment playback started, long before the intro ended. That is
 * the reported bug.
 *
 * An earlier fix removed these lines from the *alignment anchor* but left them in the displayed
 * document, which is why the timing was right while the lyrics still started too early. Both need
 * the same rule, so it lives here.
 *
 * A **denylist** is used deliberately: anything unrecognised is treated as a lyric, so an unusual
 * credit form degrades to showing a stray line rather than silently deleting real lyrics.
 */
internal object LyricMetadata {

    /**
     * Role prefixes that introduce a credit, e.g. `作词：张三`, `Producer: X`.
     *
     * Matched against the short head *before* a separator, so "作词" only matches as the label.
     */
    private val CREDIT_PREFIXES = listOf(
        "作词", "作曲", "编曲", "制作人", "出品", "监制", "混音", "母带", "录音",
        "吉他", "贝斯", "鼓", "键盘", "和声", "合唱", "录音室", "录音棚", "统筹",
        "企划", "策划", "封面", "设计", "发行", "词", "曲", "OP", "SP",
        "Mixed", "Mastered", "Produced", "Producer", "Written", "Composed",
        "Arranged", "Lyrics", "Music by", "Guitar", "Bass", "Drums",
    )

    /**
     * Credit phrasings with no separator before the name, e.g. "Written by A. Person".
     *
     * Kept separate from [CREDIT_PREFIXES] because these must match the whole opening phrase: a
     * bare "Written" prefix would also swallow a lyric that happens to start with that word.
     */
    private val VERB_CREDIT_PREFIXES = listOf(
        "Written by ", "Composed by ", "Produced by ", "Arranged by ", "Lyrics by ",
        "Mixed by ", "Mastered by ", "Music by ", "Performed by ", "Recorded by ",
    )

    /**
     * A bare attribution: a short label ending in a colon with nothing after it.
     *
     * Real examples from measured files: `林达浪：` and `Starling8:` — the performer credited on
     * its own line. These are not role credits, so [isCreditLine] misses them, yet they are just as
     * much "not a lyric" and they carry their own timestamps.
     */
    private val ATTRIBUTION = Regex("^([^:：\\-–—]{1,20})[:：]\\s*$")

    /** True when [text] looks like a credit line (a role label followed by a value). */
    fun isCreditLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true

        // "Written by X" / "Produced by X": a credit verb phrase with no separator.
        if (VERB_CREDIT_PREFIXES.any { t.startsWith(it, ignoreCase = true) }) return true

        // "作词：张三" / "Producer: X": a short head before a separator.
        val head = Regex("^([^:：\\-–—]{1,12})[:：\\-–—]").find(t)?.groupValues?.get(1)?.trim()
            ?: return false
        return CREDIT_PREFIXES.any { prefix ->
            head.equals(prefix, ignoreCase = true) ||
                // "作词人", "制作团队" etc.
                head.startsWith(prefix, ignoreCase = true)
        }
    }

    /** True when [text] should never be displayed or highlighted as a lyric. */
    fun isMetadataLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (isCreditLine(t)) return true
        return ATTRIBUTION.matches(t)
    }

    /**
     * Drops the leading run of metadata lines from [items].
     *
     * Only a **contiguous leading run** is removed: a credit-looking line in the middle of a song
     * (a "作词：…" appearing again, or a lyric containing a colon) is left alone. If every line
     * looks like metadata the input is returned unchanged, because showing the document is better
     * than showing "no lyrics".
     */
    fun <T> stripLeading(items: List<T>, textOf: (T) -> String): List<T> {
        val firstReal = items.indexOfFirst { !isMetadataLine(textOf(it)) }
        if (firstReal <= 0) return items
        return items.subList(firstReal, items.size)
    }
}
