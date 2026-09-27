package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the app's decision to parse known-YRC payloads with `NeteaseYrcParser` directly.
 *
 * Both detectors accept a YRC payload — measured on the Latin-script sample below, `AutoParser().canParse`
 * and `NeteaseYrcParser.canParse` are both true — and `AutoParser` orders `LyricifySyllableParser`
 * first. So *which* parser claims the document depends on detector ordering rather than on the
 * format, which is why the app names the parser it knows is right for this field.
 *
 * Note what these tests do **not** claim: on this sample auto detection produced identical, correct
 * syllable timings. An earlier version of this file asserted that auto detection was broken here,
 * but its assertions compared a value with itself and could not fail. The tests below were rewritten
 * to state what is actually true, so they pin the behaviour rather than the intent.
 */
class LyricSourceSelectionTest {

    /** Shaped like NetEase YRC, but with Latin syllable text. */
    private val latinYrc = """
        [12580,3470](12580,250,0)Hello(12830,300,0) world(13130,300,0)!
        [16500,3200](16500,280,0)Another(16780,320,0) line(17100,300,0) here
    """.trimIndent()

    @Test
    fun `both detectors accept a YRC payload`() {
        // This is the overlap that makes the app name the parser explicitly.
        assertTrue(AutoParser().canParse(latinYrc))
        assertTrue(NeteaseYrcParser.canParse(latinYrc))
    }

    @Test
    fun `direct YRC parsing preserves syllable timing for Latin lyrics`() {
        val lyrics = NeteaseYrcParser.parse(latinYrc)
        val karaoke = lyrics.lines.filterIsInstance<KaraokeLine>()
        assertTrue("expected karaoke lines", karaoke.isNotEmpty())

        // The failure this would catch is a payload parsed as line-level text, which shows up as
        // zero or one "syllable" per line.
        val syllables = karaoke.sumOf { it.syllables.size }
        assertTrue("expected syllable-level timing, got $syllables syllables", syllables > karaoke.size)

        karaoke.forEach { line ->
            line.syllables.forEach { s ->
                assertTrue("syllable '${s.content}' must have positive duration", s.end > s.start)
            }
        }
    }

    @Test
    fun `on this sample auto detection agrees with the direct parser`() {
        // Records the measured result rather than assuming a bug. If a future version starts
        // disagreeing, this fails and points at detector ordering — which is exactly the signal the
        // app's explicit parser choice is defending against.
        val auto = AutoParser().parse(latinYrc)
        val direct = NeteaseYrcParser.parse(latinYrc)

        assertEquals("line count", direct.lines.size, auto.lines.size)
        assertEquals(
            "syllable timings",
            direct.lines.filterIsInstance<KaraokeLine>().flatMap { it.syllables.map { s -> "$s" } },
            auto.lines.filterIsInstance<KaraokeLine>().flatMap { it.syllables.map { s -> "$s" } },
        )
    }
}
