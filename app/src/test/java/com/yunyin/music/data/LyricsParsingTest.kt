package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the word-by-word lyrics pipeline against real files from the AMLL TTML DB.
 *
 * These are the two formats the app actually feeds to `lyrics-core`, so parsing them here
 * proves the renderer receives syllable-level timing (not just line timing) without
 * needing a device.
 */
class LyricsParsingTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `AMLL TTML parses into karaoke lines with syllable timing`() {
        val ttml = resource("lyrics/amll_sample.ttml")

        assertTrue("TTMLParser should recognise the document", TTMLParser().canParse(ttml))

        val lyrics = TTMLParser().parse(ttml)
        assertTrue("expected parsed lines", lyrics.lines.isNotEmpty())

        val karaoke = lyrics.lines.filterIsInstance<KaraokeLine>()
        assertTrue("expected at least one syllable-timed line", karaoke.isNotEmpty())

        // Word-by-word means more syllables than lines: verify the ratio is meaningful.
        val totalSyllables = karaoke.sumOf { it.syllables.size }
        assertTrue(
            "expected more syllables ($totalSyllables) than karaoke lines (${karaoke.size})",
            totalSyllables > karaoke.size,
        )

        // Every syllable must carry a sane, ordered time range.
        karaoke.forEach { line ->
            line.syllables.forEach { syllable ->
                assertTrue(
                    "syllable '${syllable.content}' has non-positive duration",
                    syllable.end > syllable.start,
                )
                assertTrue(
                    "syllable '${syllable.content}' escapes its line range",
                    syllable.start >= line.start && syllable.end <= line.end,
                )
            }
        }
    }

    @Test
    fun `NetEase YRC parses into syllable level timing`() {
        val yrc = resource("lyrics/amll_sample.yrc")

        assertTrue("NeteaseYrcParser should recognise the payload", NeteaseYrcParser.canParse(yrc))

        val lyrics = NeteaseYrcParser.parse(yrc)
        val karaoke = lyrics.lines.filterIsInstance<KaraokeLine>()
        assertTrue("expected syllable-timed YRC lines", karaoke.isNotEmpty())

        val first = karaoke.first { it.syllables.size > 1 }
        assertTrue("syllables should be time-ordered", first.syllables.zipWithNext().all { (a, b) -> b.start >= a.start })
        assertEquals("line start should match its first syllable", first.syllables.first().start, first.start)
    }

    @Test
    fun `AutoParser routes both formats and yields karaoke data`() {
        val parser = AutoParser()

        val ttml = parser.parse(resource("lyrics/amll_sample.ttml"))
        val yrc = parser.parse(resource("lyrics/amll_sample.yrc"))

        assertTrue("TTML should produce karaoke lines", ttml.lines.any { it is KaraokeLine })
        assertTrue("YRC should produce karaoke lines", yrc.lines.any { it is KaraokeLine })
    }
}
