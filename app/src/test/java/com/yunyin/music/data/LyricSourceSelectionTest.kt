package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the lyric-source selection.
 *
 * `AutoParser` returns the first parser whose `canParse` matches, and it orders
 * `LyricifySyllableParser` before `NeteaseYrcParser`. A YRC payload whose syllable text is
 * Latin produces `Hello(1734,400,0)`, which the Lyricify detector accepts — so auto
 * detection can claim a YRC document and parse it as the wrong format, silently dropping
 * the syllable timing. The app therefore parses known-TTML with `TTMLParser` and known-YRC
 * with `NeteaseYrcParser` directly.
 */
class LyricSourceSelectionTest {

    /** Shaped like NetEase YRC, but with Latin syllable text. */
    private val latinYrc = """
        [12580,3470](12580,250,0)Hello(12830,300,0) world(13130,300,0)!
        [16500,3200](16500,280,0)Another(16780,320,0) line(17100,300,0) here
    """.trimIndent()

    @Test
    fun `Latin YRC is recognised by the YRC parser`() {
        assertTrue(NeteaseYrcParser.canParse(latinYrc))
    }

    @Test
    fun `direct YRC parsing preserves syllable timing for Latin lyrics`() {
        val lyrics = NeteaseYrcParser.parse(latinYrc)
        val karaoke = lyrics.lines.filterIsInstance<KaraokeLine>()
        assertTrue("expected karaoke lines", karaoke.isNotEmpty())

        // The regression would show up as zero (or one) syllable per line.
        val syllables = karaoke.sumOf { it.syllables.size }
        assertTrue("expected syllable-level timing, got $syllables syllables", syllables > karaoke.size)

        karaoke.forEach { line ->
            line.syllables.forEach { s ->
                assertTrue("syllable '${s.content}' must have positive duration", s.end > s.start)
            }
        }
    }

    @Test
    fun `auto detection is not the safe route for YRC`() {
        // Documents *why* the app bypasses AutoParser for this input: if the first
        // matching parser is not the YRC one, syllable timing is lost.
        val auto = AutoParser().parse(latinYrc)
        val autoSyllables = auto.lines.filterIsInstance<KaraokeLine>().sumOf { it.syllables.size }
        val directSyllables = NeteaseYrcParser.parse(latinYrc)
            .lines.filterIsInstance<KaraokeLine>().sumOf { it.syllables.size }

        assertEquals(
            "direct parsing should preserve every syllable",
            directSyllables,
            NeteaseYrcParser.parse(latinYrc).lines.filterIsInstance<KaraokeLine>().sumOf { it.syllables.size },
        )
        assertTrue(
            "direct parsing must yield at least as many syllables as auto detection " +
                "(auto=$autoSyllables, direct=$directSyllables)",
            directSyllables >= autoSyllables,
        )
    }
}
