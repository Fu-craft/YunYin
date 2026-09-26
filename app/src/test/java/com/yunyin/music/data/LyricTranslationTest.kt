package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Translation matching.
 *
 * The regression this guards: NetEase's `tlyric` timestamps are not always equal to the
 * lyric line starts (measured on real English tracks: 2/60 exact but 55/60 within 300ms),
 * so matching by exact timestamp left most lines untranslated.
 */
class LyricTranslationTest {

    private fun karaokeLine(start: Int, text: String) = KaraokeLine.MainKaraokeLine(
        syllables = listOf(KaraokeSyllable(content = text, start = start, end = start + 500)),
        translation = null,
        alignment = KaraokeAlignment.Unspecified,
        start = start,
        end = start + 500,
    )

    @Test
    fun `parses standard timestamps`() {
        val map = LyricTranslation.parse("[00:11.368]hello\n[00:14.62]world\n[01:05.5]late")
        assertEquals("hello", map[11_368])
        assertEquals("world", map[14_620])
        assertEquals("late", map[65_500])
    }

    @Test
    fun `parses stacked timestamps on one line`() {
        // A repeated chorus arrives as several tags followed by one text.
        val map = LyricTranslation.parse("[00:12.00][01:40.00]again")
        assertEquals("again", map[12_000])
        assertEquals("again", map[100_000])
    }

    @Test
    fun `ignores metadata tags with no timestamp`() {
        val map = LyricTranslation.parse("[by:someone]\n[00:01.00]real line")
        assertEquals(1, map.size)
        assertEquals("real line", map[1_000])
    }

    @Test
    fun `nearestWithin picks the closest and respects tolerance`() {
        val starts = listOf(1_000, 2_000, 5_000)
        assertEquals(2_000, LyricTranslation.nearestWithin(starts, 2_200, 500))
        assertEquals(1_000, LyricTranslation.nearestWithin(starts, 1_100, 500))
        assertNull("out of tolerance", LyricTranslation.nearestWithin(starts, 3_500, 500))
        assertNull("empty list", LyricTranslation.nearestWithin(emptyList(), 100, 500))
    }

    @Test
    fun `translation is attached when timestamps are offset by up to the tolerance`() {
        // Line starts at 5790ms; the translation sits at 6000ms -> 210ms apart, which the
        // old exact-match code missed (this is the Cruel Summer case).
        val lyrics = SyncedLyrics(
            lines = listOf(
                karaokeLine(5_790, "Silent night"),
                karaokeLine(10_000, "Blurry dream"),
            ),
        )
        val merged = LyricTranslation.merge(lyrics, "[00:06.000]悄无声息的夜\n[00:10.400]模糊不清的梦")

        assertEquals("悄无声息的夜", merged.lines[0].translationText())
        assertEquals("模糊不清的梦", merged.lines[1].translationText())
    }

    @Test
    fun `existing translations are preserved and only gaps filled`() {
        val lines = listOf(
            SyncedLine(content = "one", translation = "已有一", start = 1_000, end = 2_000),
            SyncedLine(content = "two", translation = null, start = 2_000, end = 3_000),
        )
        val merged = LyricTranslation.merge(SyncedLyrics(lines = lines), "[00:01.000]新的一\n[00:02.000]新的二")

        assertEquals("existing must win", "已有一", merged.lines[0].translationText())
        assertEquals("gap must be filled", "新的二", merged.lines[1].translationText())
    }

    @Test
    fun `blank translation input leaves lyrics untouched`() {
        val lyrics = SyncedLyrics(lines = listOf(karaokeLine(1_000, "x")))
        assertEquals(lyrics, LyricTranslation.merge(lyrics, null))
        assertEquals(lyrics, LyricTranslation.merge(lyrics, "   "))
        assertEquals(lyrics, LyricTranslation.merge(lyrics, "[by:meta only]"))
    }
}

/** Test-only accessor for the translation on either line type. */
private fun com.mocharealm.accompanist.lyrics.core.model.ISyncedLine.translationText(): String? =
    when (this) {
        is KaraokeLine -> translation
        is SyncedLine -> translation
        else -> null
    }
