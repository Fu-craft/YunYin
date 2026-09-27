package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards which lyric line reaches the status bar.
 *
 * The ticker shows exactly one line, so the selection rule is the whole feature. The case that
 * matters most is overlapping ends: a line's end is extended to cover its accompaniment vocals, so a
 * naive `[start, end)` range test holds the previous line while the next one is already being sung.
 */
class LyricTickerTest {

    private fun synced(vararg spans: Pair<Int, Int>) = spans.mapIndexed { i, (s, e) ->
        SyncedLine(content = "line$i", translation = null, start = s, end = e)
    }

    @Test
    fun `before the first line there is nothing to show`() {
        // The lead-in has no lyric yet; the caller shows the song title instead of guessing.
        val lines = synced(5_000 to 9_000)
        assertNull(LyricTicker.lineAt(lines, 0))
        assertNull(LyricTicker.lineAt(lines, 4_999))
    }

    @Test
    fun `the current line is the latest one that has started`() {
        val lines = synced(0 to 1_000, 1_000 to 2_000, 2_000 to 3_000)
        assertEquals("line0", LyricTicker.contentOf(LyricTicker.lineAt(lines, 0)))
        assertEquals("line1", LyricTicker.contentOf(LyricTicker.lineAt(lines, 1_500)))
        assertEquals("line2", LyricTicker.contentOf(LyricTicker.lineAt(lines, 2_500)))
    }

    @Test
    fun `an overlapping end does not hold the previous line`() {
        // Line 0's end (2_000) runs past line 1's start (1_500) because of accompaniment vocals. A
        // range test would still report line 0 at 1_600.
        val lines = synced(0 to 2_000, 1_500 to 3_000)
        assertEquals("line1", LyricTicker.contentOf(LyricTicker.lineAt(lines, 1_600)))
    }

    @Test
    fun `an instrumental gap keeps the line just sung`() {
        // Matching the on-screen renderer: during a gap the position stays on the last line rather
        // than jumping to the upcoming one or showing nothing.
        val lines = synced(0 to 1_000, 20_000 to 25_000)
        assertEquals("line0", LyricTicker.contentOf(LyricTicker.lineAt(lines, 10_000)))
    }

    @Test
    fun `a karaoke line is joined from its syllables`() {
        val line = KaraokeLine.MainKaraokeLine(
            syllables = listOf(
                KaraokeSyllable(content = "普通", start = 0, end = 400, phonetic = null),
                KaraokeSyllable(content = "朋友", start = 400, end = 900, phonetic = null),
            ),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 0,
            end = 900,
            phonetic = null,
        )
        assertEquals("普通朋友", LyricTicker.contentOf(line))
    }

    @Test
    fun `the translation is not appended`() {
        // The ticker is one line in the status bar; "content\ntranslation" would be truncated midway
        // and read as a rendering fault. The clipboard copy deliberately differs here.
        val line = SyncedLine(content = "hello", translation = "你好", start = 0, end = 500)
        assertEquals("hello", LyricTicker.contentOf(line))
    }

    @Test
    fun `a blank line yields nothing rather than whitespace`() {
        assertEquals(null, LyricTicker.contentOf(SyncedLine("   ", null, 0, 500)))
        assertEquals(null, LyricTicker.contentOf(null))
    }
}
