package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the translation onto 词幕 (Lyricon)'s model.
 *
 * These pin the constraints Lyricon documents — milliseconds, monotonically increasing line times,
 * word ranges inside their own line, no empty rows — because a violation is not a crash but a
 * mis-drawn status bar, which is far harder to diagnose than a test failure.
 */
class LyriconMapperTest {

    private fun track() = Track(
        id = 42L,
        name = "普通朋友",
        artists = listOf("陶喆"),
        albumName = "I'm OK",
        albumId = 1L,
        coverUrl = null,
        durationMs = 200_000L,
    )

    private fun synced(vararg spans: Pair<Int, Int>) = SyncedLyrics(
        lines = spans.mapIndexed { index, (start, end) ->
            SyncedLine(content = "line$index", translation = null, start = start, end = end)
        },
    )

    private fun karaoke(
        syllables: List<KaraokeSyllable>,
        start: Int,
        end: Int,
        translation: String? = null,
        phonetic: String? = null,
    ) = KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = translation,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
        phonetic = phonetic,
    )

    @Test
    fun `song identity and duration are carried over`() {
        val song = LyriconMapper.toSong(track(), synced(0 to 1000))
        assertEquals("42", song.id)
        assertEquals("普通朋友", song.name)
        assertEquals("陶喆", song.artist)
        assertEquals(200_000L, song.duration)
    }

    @Test
    fun `a null document still yields a song, just without lyrics`() {
        // The song must still be published: Lyricon shows the track even when no lyrics exist, and a
        // null return here would leave the status bar blank instead of showing what is playing.
        val song = LyriconMapper.toSong(track(), null)
        assertEquals("42", song.id)
        assertTrue("expected no lyric lines", song.lyrics.isNullOrEmpty())
    }

    @Test
    fun `line times are milliseconds and preserve order`() {
        val song = LyriconMapper.toSong(track(), synced(0 to 1000, 1000 to 2500, 2500 to 4000))
        val begins = song.lyrics.orEmpty().map { it.begin }
        assertEquals(listOf(0L, 1000L, 2500L), begins)
        // Monotonic, which is what Lyricon's model asks for.
        assertEquals(begins.sorted(), begins)
    }

    @Test
    fun `a zero-length line is widened so end is after begin`() {
        // Some sources emit end == start for instant lines. A zero-length range makes progress
        // maths divide oddly in the consumer, so it is not passed through.
        val song = LyriconMapper.toSong(
            track(),
            SyncedLyrics(
                lines = listOf(SyncedLine(content = "x", translation = null, start = 500, end = 500)),
            ),
        )
        val line = song.lyrics!!.single()
        assertTrue("end must exceed begin", line.end > line.begin)
        assertEquals(501L, line.end)
    }

    @Test
    fun `word ranges stay inside their line`() {
        val line = karaoke(
            syllables = listOf(
                KaraokeSyllable(content = "普通", start = 0, end = 400, phonetic = null),
                KaraokeSyllable(content = "朋友", start = 400, end = 900, phonetic = null),
            ),
            start = 0,
            end = 900,
        )
        val mapped = LyriconMapper.toSong(track(), SyncedLyrics(lines = listOf(line))).lyrics!!.single()
        mapped.words.orEmpty().forEach { word ->
            assertTrue(
                "word ${word.begin}..${word.end} escapes line ${mapped.begin}..${mapped.end}",
                word.begin >= mapped.begin && word.end <= mapped.end,
            )
        }
    }

    @Test
    fun `a karaoke line keeps its words and its translation separately`() {
        // The translation belongs in `translation`, not appended to `words`: Lyricon karaoke-fills
        // `words` against the line range while translations are drawn as their own row.
        val line = karaoke(
            syllables = listOf(KaraokeSyllable(content = "hello", start = 0, end = 500, phonetic = null)),
            start = 0,
            end = 500,
            translation = "你好",
        )
        val mapped = LyriconMapper.toSong(track(), SyncedLyrics(lines = listOf(line))).lyrics!!.single()
        assertEquals("hello", mapped.text)
        assertEquals("你好", mapped.translation)
        assertEquals(1, mapped.words?.size)
    }

    @Test
    fun `phonetic becomes the roma field`() {
        // The vendored model calls it `phonetic`; Lyricon calls the same thing `roma`.
        val line = karaoke(
            syllables = listOf(KaraokeSyllable(content = "你好", start = 0, end = 500, phonetic = null)),
            start = 0,
            end = 500,
            phonetic = "ni hao",
        )
        val mapped = LyriconMapper.toSong(track(), SyncedLyrics(lines = listOf(line))).lyrics!!.single()
        assertEquals("ni hao", mapped.roma)
    }

    @Test
    fun `an empty line is dropped rather than published as a blank row`() {
        // A blank row renders as an empty line in the status bar, which reads as a rendering fault.
        val song = LyriconMapper.toSong(
            track(),
            SyncedLyrics(
                lines = listOf(
                    SyncedLine(content = "", translation = null, start = 0, end = 500),
                    SyncedLine(content = "real", translation = null, start = 500, end = 1000),
                ),
            ),
        )
        assertEquals(1, song.lyrics?.size)
        assertEquals("real", song.lyrics!!.single().text)
    }

    @Test
    fun `a plain line carries no words`() {
        val mapped = LyriconMapper.toSong(track(), synced(0 to 1000)).lyrics!!.single()
        assertEquals("line0", mapped.text)
        assertNull("a line-level lyric must not claim word timing", mapped.words)
    }
}
