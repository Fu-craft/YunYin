package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.copy
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Folds a document's simultaneous sibling lines into translations.
 *
 * ## The bug this fixes
 *
 * A payload can carry a line's translation as a **separate line with the same start time**. NetEase
 * does exactly this in its YRC track, and TTML can express it with a second `<p>` or a second `<div>`.
 * Nothing marked those lines as translations, so they were parsed as ordinary lyric lines: the
 * renderer drew the translation as its own row and gave it the word-by-word fill — the translation was
 * played as if it were the lyric.
 *
 * Measured (see the repository's diagnostics): a two-line bilingual YRC produced **two**
 * `KaraokeLine`s at `start=12580`, one `Hello world` and one `你好世界`, neither carrying a
 * translation. The same payload in LRC form is folded correctly by the library's own LRC parser, which
 * is the precedent this follows rather than a new heuristic.
 *
 * ## Why this runs only on the YRC document
 *
 * In NetEase's YRC there is no concept of simultaneous *voices* — the format has no agent — so two
 * lines sharing a start can only be a line and its translation. TTML **does** have agents, and a duet
 * legitimately has two voices singing different words at the same moment; folding those would delete
 * a real lyric, which is worse than the bug. TTML already folds the translation-span form correctly
 * (`<span ttm:role="x-translation">`), so it needs nothing here.
 *
 * So the rule is deliberately narrow: same start, and the later line becomes the earlier one's
 * translation. Existing translations are preserved and appended to, never overwritten.
 */
internal object LyricLineFolding {

    /**
     * Folds every line that shares its predecessor's start time into that predecessor's translation.
     *
     * Order is preserved, so the surviving line keeps the earlier position in the list, and the
     * document's time ordering is unaffected because only zero-offset duplicates are removed.
     */
    fun foldSimultaneousTranslations(lyrics: SyncedLyrics): SyncedLyrics {
        if (lyrics.lines.size < 2) return lyrics

        val out = ArrayList<ISyncedLine>(lyrics.lines.size)
        for (line in lyrics.lines) {
            val previous = out.lastOrNull()
            if (previous != null && previous.start == line.start) {
                val text = textOf(line)
                if (text.isNotEmpty()) {
                    out[out.lastIndex] = withAppendedTranslation(previous, text)
                }
                // The line is dropped either way: a same-start line that carries no text has nothing
                // to contribute, and keeping it would render as a blank row.
                continue
            }
            out.add(line)
        }
        return if (out.size == lyrics.lines.size) lyrics else lyrics.copy(lines = out)
    }

    private fun textOf(line: ISyncedLine): String = when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> ""
    }.toString().trim()

    private fun withAppendedTranslation(line: ISyncedLine, text: String): ISyncedLine {
        val existing = when (line) {
            is KaraokeLine -> line.translation
            is SyncedLine -> line.translation
            else -> null
        }?.trim().orEmpty()
        // Appended rather than replaced: a line can legitimately have several same-start siblings (a
        // translation plus a transliteration), and dropping one would lose what the document said.
        val combined = if (existing.isEmpty()) text else "$existing\n$text"
        return when (line) {
            is KaraokeLine -> line.copy(translation = combined)
            is SyncedLine -> line.copy(translation = combined)
            else -> line
        }
    }
}
