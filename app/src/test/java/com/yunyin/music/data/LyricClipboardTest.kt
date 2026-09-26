package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what a long-pressed lyric copies.
 *
 * The string itself is trivial; what these cover is the set of decisions around it — how a karaoke
 * line is reconstructed from syllables, when a translation joins it, and the several cases that
 * should copy nothing rather than a blank line.
 */
class LyricClipboardTest {

    private fun syllable(content: String, start: Int = 0, end: Int = 100) =
        KaraokeSyllable(content = content, start = start, end = end)

    private fun karaoke(
        contents: List<String>,
        translation: String? = null,
    ) = KaraokeLine.MainKaraokeLine(
        syllables = contents.mapIndexed { i, c -> syllable(c, start = i * 100, end = i * 100 + 100) },
        translation = translation,
        alignment = KaraokeAlignment.Start,
        start = 0,
        end = contents.size * 100,
    )

    @Test
    fun `karaoke syllables concatenate without inserting spaces`() {
        // Each syllable already carries its own spacing, so a separator here would add gaps the
        // lyric does not have.
        val line = karaoke(listOf("Hel", "lo ", "wor", "ld"))
        assertEquals("Hello world", LyricClipboard.textFor(line))
    }

    @Test
    fun `a translation is copied on its own line`() {
        val line = karaoke(listOf("Of ", "emotions"), translation = "因为感情")
        assertEquals("Of emotions\n因为感情", LyricClipboard.textFor(line))
    }

    @Test
    fun `a blank translation does not add an empty line`() {
        val line = karaoke(listOf("Hello"), translation = "   ")
        assertEquals("Hello", LyricClipboard.textFor(line))
    }

    @Test
    fun `a synced line copies its content`() {
        val line = SyncedLine(content = "Scared of you leaving", translation = null, start = 0, end = 2000)
        assertEquals("Scared of you leaving", LyricClipboard.textFor(line))
    }

    @Test
    fun `a synced line with a translation copies both`() {
        val line = SyncedLine(
            content = "Scared of you leaving",
            translation = "因为害怕你离开",
            start = 0,
            end = 2000,
        )
        assertEquals("Scared of you leaving\n因为害怕你离开", LyricClipboard.textFor(line))
    }

    @Test
    fun `a line with only whitespace copies nothing`() {
        assertNull(LyricClipboard.textFor(karaoke(listOf("   ", "\t"))))
        assertNull(LyricClipboard.textFor(SyncedLine(content = "  ", translation = null, start = 0, end = 1)))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Hello", LyricClipboard.textFor(karaoke(listOf("  Hel", "lo  "))))
    }
}
