package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation parsing and the folding of simultaneous lines.
 *
 * Two reported bugs live here, both measured before being fixed:
 *
 *  1. NetEase's `ytlrc` is **YRC-shaped** (`[start,dur](wordStart,wordDur,?)text`), not LRC-shaped. The
 *     parser only understood `[mm:ss.xx]`, so it matched nothing and returned an empty map — every
 *     karaoke song silently lost its translation, even though the repository had deliberately picked
 *     the better-aligned source for exactly those songs.
 *  2. A line's translation can arrive as a **second line with the same start time** (NetEase's YRC does
 *     this; TTML can do it with a second `<p>`). Nothing marked it as a translation, so it was drawn
 *     as a lyric row and given the word-by-word fill: the translation was played as the lyric.
 */
class LyricTranslationParsingTest {

    // --------------------------------------------------------------- YRC-shaped translation

    @Test
    fun `a YRC-shaped translation payload is parsed`() {
        // `ytlrc` looks like this, and it is what the repository prefers for karaoke songs.
        val raw = """
            [12580,3470](12580,250,0)你好(12830,300,0)世界
            [16500,3200](16500,280,0)第二(16780,320,0)行
        """.trimIndent()

        val entries = LyricTranslation.parse(raw)
        assertEquals(2, entries.size)
        assertEquals("你好世界", entries[12580])
        assertEquals("第二行", entries[16500])
    }

    @Test
    fun `an LRC-shaped translation payload still parses`() {
        // The original shape must keep working; the YRC support is additive.
        val raw = "[00:12.00]你好世界\n[00:16.50]第二行"
        val entries = LyricTranslation.parse(raw)
        assertEquals("你好世界", entries[12_000])
        assertEquals("第二行", entries[16_500])
    }

    @Test
    fun `an LRC translation with stacked timestamps parses every tag`() {
        // A repeated chorus line arrives as `[t1][t2]text`.
        val entries = LyricTranslation.parse("[00:12.00][01:40.00]副歌")
        assertEquals("副歌", entries[12_000])
        assertEquals("副歌", entries[100_000])
    }

    @Test
    fun `a YRC translation merges onto matching lines`() {
        val base = SyncedLyrics(
            lines = listOf(
                SyncedLine("Hello world", null, 12_580, 16_050),
                SyncedLine("Second line", null, 16_500, 19_700),
            ),
        )
        val raw = "[12580,3470](12580,250,0)你好世界\n[16500,3200](16500,280,0)第二行"

        val merged = LyricTranslation.merge(base, raw)
        assertEquals("你好世界", (merged.lines[0] as SyncedLine).translation)
        assertEquals("第二行", (merged.lines[1] as SyncedLine).translation)
    }

    // --------------------------------------------------------------- folding simultaneous lines

    private fun karaoke(start: Int, end: Int, content: String) = KaraokeLine.MainKaraokeLine(
        syllables = listOf(KaraokeSyllable(content, start, end, null)),
        translation = null,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
        phonetic = null,
    )

    @Test
    fun `a bilingual YRC document folds its translation into the line above it`() {
        // The measured regression: 4 lines in, 2 out, each carrying its translation — instead of the
        // translation being a karaoke line of its own.
        val raw = """
            [12580,3470](12580,250,0)Hello(12830,300,0) world
            [12580,3470](12580,250,0)你好(12830,300,0)世界
            [16500,3200](16500,280,0)Second(16780,320,0) line
            [16500,3200](16500,280,0)第二(16780,320,0)行
        """.trimIndent()

        val parsed = NeteaseYrcParser.parse(raw)
        assertEquals("the raw parse keeps both lines", 4, parsed.lines.size)

        val folded = LyricLineFolding.foldSimultaneousTranslations(parsed)
        assertEquals(2, folded.lines.size)

        val first = folded.lines[0] as KaraokeLine
        assertEquals(12_580, first.start)
        assertEquals("Hello world", first.syllables.joinToString("") { it.content })
        assertEquals("你好世界", first.translation)

        val second = folded.lines[1] as KaraokeLine
        assertEquals("Second line", second.syllables.joinToString("") { it.content })
        assertEquals("第二行", second.translation)
    }

    @Test
    fun `the folded line keeps its word timing`() {
        // Folding must not cost the karaoke fill: the surviving line is the original, only its
        // translation changed.
        val line = karaoke(12_580, 16_050, "Hello").let {
            it.copy(translation = null)
        }
        val lyrics = SyncedLyrics(lines = listOf(line, karaoke(12_580, 16_050, "你好")))
        val folded = LyricLineFolding.foldSimultaneousTranslations(lyrics)

        val survivor = folded.lines.single() as KaraokeLine
        assertEquals(1, survivor.syllables.size)
        assertEquals(12_580, survivor.syllables.single().start)
        assertEquals(16_050, survivor.syllables.single().end)
    }

    @Test
    fun `distinct timestamps are never folded`() {
        // The guard against over-eager folding: only an identical start folds. These are two real
        // consecutive lines and both must survive.
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("first", null, 1_000, 2_000),
                SyncedLine("second", null, 2_000, 3_000),
            ),
        )
        val folded = LyricLineFolding.foldSimultaneousTranslations(lyrics)
        assertEquals(2, folded.lines.size)
        assertEquals("second", (folded.lines[1] as SyncedLine).content)
    }

    @Test
    fun `several same-start siblings are all appended`() {
        // A line can carry a translation and a transliteration as separate same-start lines; both are
        // kept, not just the first.
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("Hello", null, 1_000, 2_000),
                SyncedLine("你好", null, 1_000, 2_000),
                SyncedLine("ni hao", null, 1_000, 2_000),
            ),
        )
        val folded = LyricLineFolding.foldSimultaneousTranslations(lyrics)
        assertEquals(1, folded.lines.size)
        assertEquals("你好\nni hao", (folded.lines.single() as SyncedLine).translation)
    }

    @Test
    fun `a document with no simultaneous lines is returned unchanged`() {
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("a", null, 1_000, 2_000),
                SyncedLine("b", null, 3_000, 4_000),
            ),
        )
        assertTrue(
            "an untouched document should be the same instance, so nothing re-measures needlessly",
            foldedIsSameInstance(lyrics),
        )
    }

    private fun foldedIsSameInstance(lyrics: SyncedLyrics): Boolean =
        LyricLineFolding.foldSimultaneousTranslations(lyrics) === lyrics
}
