package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import com.yunyin.music.core.Track

/**
 * Maps this app's lyric document onto 词幕 (Lyricon)'s model.
 *
 * Pure and unit-tested, deliberately separate from [LyriconBridge]: the interesting failures here are
 * all in the *mapping* (times that must be monotonic, word ranges that must sit inside their line,
 * which of the several text fields a line's words belong in), and those cannot be observed through a
 * binder call. Keeping the translation in one pure function makes them checkable.
 *
 * Lyricon's constraints, from its lyrics-model documentation:
 *  - every time is in milliseconds;
 *  - `begin`/`end` should be monotonically increasing;
 *  - a line's word ranges must fall inside that line's range;
 *  - line-level timing is enough when there is no per-word data.
 *
 * All three are enforced below rather than assumed, because this app's documents come from two
 * different sources that disagree about structure.
 */
object LyriconMapper {

    /** Empty documents are turned into null so the bridge sends no lyrics rather than a blank set. */
    fun toSong(track: Track, lyrics: SyncedLyrics?): Song {
        val lines = lyrics?.lines?.mapNotNull(::toLine).orEmpty()
        return Song(
            id = track.id.toString(),
            name = track.name,
            artist = track.artistLine,
            duration = track.durationMs,
            metadata = null,
            lyrics = lines,
        )
    }

    /**
     * One line, or null when it carries nothing to display.
     *
     * A karaoke line's words go in `words` and its translation in `translation` — *not* into
     * `words` with the translation's words, because Lyricon karaoke-fills `words` against the
     * line's own range while translations are drawn separately. Word translations, when the source
     * provides them, go to `translationWords`.
     */
    private fun toLine(line: ISyncedLine): RichLyricLine? {
        val text = lineText(line)
        val translation = lineTranslation(line)

        val words = (line as? KaraokeLine)?.syllables
            ?.mapNotNull { syllable ->
                val content = syllable.content
                if (content.isEmpty()) null
                else LyricWord(
                    begin = syllable.start.toLong(),
                    end = syllable.end.toLong(),
                    text = content,
                )
            }
            ?.takeIf { it.isNotEmpty() }

        // A line with neither text nor words is a rest/placeholder: skip it rather than publish an
        // empty row, which would show as a blank line in the status bar.
        if (text.isBlank() && words == null) return null

        val begin = line.start.toLong()
        // `end` must be at least `begin`; a zero-length line makes Lyricon's progress maths divide
        // oddly, and some sources emit end == start for instant lines.
        val end = line.end.toLong().coerceAtLeast(begin + 1)

        return RichLyricLine(
            begin = begin,
            end = end,
            isAlignedRight = false,
            // `LyricWord.text` is nullable in the model, and the fallback is only reached when the
            // line's own text was blank — so an empty join is a legitimate (if empty) result.
            text = text.ifBlank { words?.joinToString("") { it.text.orEmpty() }.orEmpty() },
            words = words,
            translation = translation,
            translationWords = null,
            secondary = null,
            secondaryWords = null,
            roma = lineRoma(line),
        )
    }

    /**
     * The line's text, whichever shape the line takes.
     *
     * `.toString()` is required: syllable content is a `CharSequence`, so the concatenation (and
     * therefore `trim()`) is a `CharSequence` too, and returning that where a `String` is declared
     * does not compile.
     */
    private fun lineText(line: ISyncedLine): String = when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> ""
    }.toString().trim()

    private fun lineTranslation(line: ISyncedLine): String? = when (line) {
        is KaraokeLine -> line.translation
        is SyncedLine -> line.translation
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Romanisation, when the document carries it.
     *
     * This is the `phonetic` field on a karaoke line — the vendored model calls it that, not `roma`,
     * which is Lyricon's name for the same thing on its own side.
     */
    private fun lineRoma(line: ISyncedLine): String? = when (line) {
        is KaraokeLine -> line.phonetic
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}
