package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.copy
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Folds a translation that the **source baked into the lyric track** as alternating lines.
 *
 * ## The payload this exists for
 *
 * Measured on the reported track (`Purple Whisper`, id 2060689311), the service returns:
 *
 * ```
 * lrc     : 53 lines — 27 Latin, 26 CJK, interleaved
 * tlyric  : empty
 * yrc     : empty
 * ytlrc   : empty
 * ```
 *
 * and the lines themselves alternate:
 *
 * ```
 * [00:08.160]I have some purple in my mind, bae.
 * [00:09.840]我心里一直有一抹紫，宝贝。
 * ```
 *
 * So the uploader put the translation **into the lyric track** instead of a translation track, and
 * nothing distinguishes those lines from lyrics. The renderer therefore drew each Chinese line as its
 * own lyric row and gave it the word-by-word fill — the translation was played as the lyric.
 *
 * That this is unusual is worth stating, because it is why the fix is a heuristic rather than a rule:
 * every other track probed keeps its translation in a separate field (Shape of You: `tlyric` 2237
 * chars; Adele's Hello: `ytlrc` 1152 chars). This one track does not.
 *
 * ## How the discrimination works, and why it needs two measurements
 *
 * "Mixed script" alone is not enough — a genuinely bilingual song is also mixed script. The two cases
 * differ in *structure*:
 *
 *  - a baked-in translation **alternates** (original, translation, original, …), so consecutive lines
 *    rarely share a script;
 *  - a bilingual song has **sections**, so consecutive lines usually do share one.
 *
 * So two measurements are required together: the fraction of adjacent pairs whose script differs, and
 * the longest run of same-script lines. Measured with both:
 *
 * | case                          | differing pairs | longest run | verdict |
 * |-------------------------------|-----------------|-------------|---------|
 * | Purple Whisper (actual)       | 0.96            | 2           | fold    |
 * | strict alternation            | 1.00            | 1           | fold    |
 * | monolingual (either language) | 0.00            | 20+         | leave   |
 * | verse 6 / hook 3              | 0.18            | 6           | leave   |
 * | verse 8 / chorus 4            | 0.13            | 8           | leave   |
 * | repeated 2-line hook pair     | 0.45            | 2           | leave   |
 *
 * The thresholds ([MIN_ALTERNATION], [MAX_RUN]) sit in the gap between 0.45 and 0.96. Either
 * measurement alone is insufficient: the run length alone would fold a document whose sections happen
 * to be two lines each, and the ratio alone would fold nothing here differently but is the weaker
 * signal on short documents.
 *
 * ## Known limitation
 *
 * This assumes the translation lines correspond one-to-one. They need not: in the same track, one
 * Chinese line translates **two** English lines
 * (`[01:19.140]` "I know you wanna ride with me." / `[01:20.670]` "Hop in the car spend some time
 * with me." / `[01:22.590]` "上车吧，陪我走一段。"). The translation is attached to the later English
 * line, so it appears from that line rather than from the start of the thought. That is a presentation
 * nuance; leaving the line unfolded is the bug being fixed, and this is strictly better.
 */
internal object LyricScriptFolding {

    /** Fraction of adjacent lettered pairs whose script must differ for this to look interleaved. */
    const val MIN_ALTERNATION = 0.75

    /** Longest run of same-script lines still consistent with alternation. */
    const val MAX_RUN = 3

    /**
     * Fewest lettered lines worth judging.
     *
     * Below this the measurements are noise: two alternating lines give a ratio of 1.0 on a single
     * pair, which decides nothing. A real interleaved document has dozens.
     */
    const val MIN_LETTERED_LINES = 8

    /** Fewest letters of a script before it can be considered the line's language at all. */
    const val MIN_SCRIPT_CHARS = 2

    /**
     * How far one script must exceed the other to be called the line's language.
     *
     * More than 2x: a line that interleaves the two roughly evenly is genuinely bilingual, not a
     * translation of a neighbouring line, and must not take part.
     */
    const val DOMINANCE = 2

    /**
     * Folds each interleaved translation line into the lyric line before it.
     *
     * Returns the input unchanged — the same instance — when the document does not look interleaved,
     * so nothing downstream re-measures a document that did not need touching.
     */
    fun foldInterleavedTranslation(lyrics: SyncedLyrics): SyncedLyrics {
        val scripts = lyrics.lines.map { scriptOf(textOf(it)) }
        val lettered = scripts.withIndex().filter { it.value != null }
        if (lettered.size < MIN_LETTERED_LINES) return lyrics

        val pairs = lettered.zipWithNext()
        val differing = pairs.count { (a, b) -> a.value != b.value }
        val alternation = differing.toDouble() / pairs.size
        if (alternation < MIN_ALTERNATION) return lyrics
        if (longestRun(lettered.map { it.value }) > MAX_RUN) return lyrics

        val out = ArrayList<ISyncedLine>(lyrics.lines.size)
        // The most recent **surviving** lettered line, which is what a translation belongs to.
        //
        // Tracking "the last script seen" instead is a bug: after folding a translation the last
        // script seen is the translation's, so the next original line also differs from it and is
        // attached too — every lyric would absorb its successor. The reference has to be the line the
        // translation is being attached to, and it has to keep pointing at that line while its
        // translation is folded in.
        var lastLetterIndex = -1
        var lastLetterScript: Script? = null

        for ((index, line) in lyrics.lines.withIndex()) {
            val script = scripts[index]
            val isTranslation = script != null &&
                lastLetterScript != null &&
                script != lastLetterScript

            if (isTranslation && lastLetterIndex >= 0) {
                val text = textOf(line)
                if (text.isNotEmpty()) {
                    out[lastLetterIndex] = withAppendedTranslation(out[lastLetterIndex], text)
                    continue
                }
            }
            out.add(line)
            if (script != null) {
                lastLetterIndex = out.lastIndex
                lastLetterScript = script
            }
        }
        return if (out.size == lyrics.lines.size) lyrics else lyrics.copy(lines = out)
    }

    private enum class Script { LATIN, CJK, OTHER }

    /**
     * Which script a line is written in, or null when that cannot be decided.
     *
     * **Dominance, not purity.** A translation may contain a Latin word — in the reported track one
     * Chinese line carries the name *Miles*:
     *
     * ```
     * [00:59.850]可到了最后，我会是你的 Miles——哪怕穿越整个宇宙，我也一定找到你
     * ```
     *
     * Requiring a line to be purely one script classified that as mixed and skipped it, leaving the
     * translation unfolded — the very bug, on one of the reported song's own lines. So a line counts as
     * a script when that script clearly dominates (more than [DOMINANCE] times the other), and only a
     * genuinely balanced line participates in neither measurement.
     *
     * A line with too few letters of either kind ([MIN_SCRIPT_CHARS]) is also null: punctuation, a
     * number, or a single letter must not be read as a language.
     */
    private fun scriptOf(text: String): Script? {
        val cjk = text.count { it.isCjk() }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            cjk >= MIN_SCRIPT_CHARS && cjk > latin * DOMINANCE -> Script.CJK
            latin >= MIN_SCRIPT_CHARS && latin > cjk * DOMINANCE -> Script.LATIN
            else -> null
        }
    }

    private fun Char.isCjk(): Boolean =
        this in '\u3400'..'\u4dbf' ||   // CJK extension A
            this in '\u4e00'..'\u9fff' || // CJK unified ideographs
            this in '\u3040'..'\u30ff' || // kana
            this in '\uac00'..'\ud7af' || // hangul syllables
            this in '\uf900'..'\ufaff'    // CJK compatibility ideographs

    private fun longestRun(values: List<Script?>): Int {
        var longest = 0
        var current = 0
        var previous: Script? = null
        for (value in values) {
            current = if (value == previous) current + 1 else 1
            longest = maxOf(longest, current)
            previous = value
        }
        return longest
    }

    private fun textOf(line: ISyncedLine): String = when (line) {
        // `joinToString` already returns a String even though a syllable's content is a CharSequence.
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> ""
    }.trim()

    private fun withAppendedTranslation(line: ISyncedLine, text: String): ISyncedLine {
        val existing = when (line) {
            is KaraokeLine -> line.translation
            is SyncedLine -> line.translation
            else -> null
        }?.trim().orEmpty()
        val combined = if (existing.isEmpty()) text else "$existing\n$text"
        return when (line) {
            is KaraokeLine -> line.copy(translation = combined)
            is SyncedLine -> line.copy(translation = combined)
            else -> line
        }
    }
}
