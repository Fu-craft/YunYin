package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-source time alignment.
 *
 * The measured failure this guards: one track's AMLL document starts at 927ms while the
 * NetEase lyric starts at 5790ms. Preferring AMLL (for its word timing) therefore played the
 * lyrics ~4.9s early — lines began before the intro had ended, on some songs only.
 */
class LyricTimeShiftTest {

    private fun karaokeLine(start: Int, end: Int, syllables: List<Pair<Int, Int>>) =
        KaraokeLine.MainKaraokeLine(
            syllables = syllables.map { (s, e) -> KaraokeSyllable("a", s, e) },
            translation = null,
            alignment = KaraokeAlignment.Unspecified,
            start = start,
            end = end,
        )

    @Test
    fun `shifts lines and syllables by a positive delta`() {
        val lyrics = SyncedLyrics(
            lines = listOf(
                karaokeLine(927, 4_000, listOf(927 to 1_500, 1_500 to 4_000)),
                karaokeLine(5_000, 8_000, listOf(5_000 to 6_000)),
            ),
        )
        val shifted = LyricTimeShift.shift(lyrics, 4_863)

        assertEquals(927 + 4_863, shifted.lines[0].start)
        assertEquals(4_000 + 4_863, shifted.lines[0].end)
        val first = shifted.lines[0] as KaraokeLine
        assertEquals(927 + 4_863, first.syllables[0].start)
        assertEquals(1_500 + 4_863, first.syllables[0].end)
        assertEquals(5_000 + 4_863, shifted.lines[1].start)
    }

    @Test
    fun `alignTo corrects a large mismatch`() {
        // AMLL says 927ms; the streamed audio's lyric starts at 5790ms.
        val amll = SyncedLyrics(lines = listOf(karaokeLine(927, 4_000, listOf(927 to 4_000))))
        val aligned = LyricTimeShift.alignTo(amll, referenceFirstLineStartMs = 5_790)

        assertEquals(
            "the first line must line up with the reference audio",
            5_790,
            aligned.lines.first().start,
        )
    }

    @Test
    fun `alignTo leaves agreeing sources untouched`() {
        val amll = SyncedLyrics(lines = listOf(karaokeLine(18_432, 22_000, listOf(18_432 to 22_000))))
        // 34ms apart — the same edit, so no shift.
        val aligned = LyricTimeShift.alignTo(amll, referenceFirstLineStartMs = 18_466)
        assertEquals(18_432, aligned.lines.first().start)
    }

    @Test
    fun `alignTo is a no-op without a reference or without content`() {
        val amll = SyncedLyrics(lines = listOf(karaokeLine(927, 4_000, listOf(927 to 4_000))))
        // No reference timestamp to align against.
        assertEquals(amll, LyricTimeShift.alignTo(amll, referenceFirstLineStartMs = null))

        // Nothing to align: returned as-is, not shifted.
        val empty = SyncedLyrics(emptyList())
        assertEquals(empty, LyricTimeShift.alignTo(empty, referenceFirstLineStartMs = 5_000))
    }

    @Test
    fun `a negative shift never produces negative timestamps`() {
        val lyrics = SyncedLyrics(
            lines = listOf(
                karaokeLine(500, 1_000, listOf(500 to 1_000)),
                karaokeLine(2_000, 2_500, listOf(2_000 to 2_500)),
            ),
        )
        val shifted = LyricTimeShift.shift(lyrics, -3_000)

        shifted.lines.forEach { line ->
            assertTrue("start must not be negative", line.start >= 0)
            assertTrue("end must not be negative", line.end >= 0)
            if (line is KaraokeLine) {
                line.syllables.forEach { s ->
                    assertTrue("syllable start must not be negative", s.start >= 0)
                    assertTrue("syllable must keep a positive duration", s.end > s.start)
                }
            }
        }
    }

    @Test
    fun `lines shifted entirely before zero are dropped`() {
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("gone", null, 100, 400),
                SyncedLine("kept", null, 9_000, 9_500),
            ),
        )
        val shifted = LyricTimeShift.shift(lyrics, -1_000)
        assertEquals(1, shifted.lines.size)
        assertEquals("kept", (shifted.lines.first() as SyncedLine).content)
    }

    @Test
    fun `result stays time-ordered`() {
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("a", null, 1_000, 2_000),
                SyncedLine("b", null, 3_000, 4_000),
                SyncedLine("c", null, 5_000, 6_000),
            ),
        )
        val shifted = LyricTimeShift.shift(lyrics, 10_000)
        assertEquals(shifted.lines.map { it.start }.sorted(), shifted.lines.map { it.start })
    }

    @Test
    fun `zero delta returns the same instance shape`() {
        val lyrics = SyncedLyrics(lines = listOf(SyncedLine("a", null, 1_000, 2_000)))
        assertEquals(lyrics, LyricTimeShift.shift(lyrics, 0))
    }

    @Test
    fun `AMLL without a reference is flagged untrustworthy for caching`() {
        // The regression: an AMLL document returned with no NetEase reference is left unshifted,
        // because there is nothing to shift it by. Returning it is fine; caching it is not,
        // because retrying later could still obtain the reference and align properly.
        assertFalse(
            "unreferenced AMLL must not be cached",
            LyricTimeShift.isAlignmentTrustworthy(
                referenceMs = null,
                fromAmll = true,
                referenceResolved = false,
            ),
        )
    }

    @Test
    fun `AMLL with a reference is trustworthy`() {
        assertTrue(
            LyricTimeShift.isAlignmentTrustworthy(
                referenceMs = 5_790,
                fromAmll = true,
                referenceResolved = true,
            ),
        )
    }

    @Test
    fun `NetEase document is always trustworthy because it is the reference`() {
        // A NetEase document needs no external reference; a null reference here means NetEase
        // simply has no lyric for the track, not that alignment information is missing.
        assertTrue(
            LyricTimeShift.isAlignmentTrustworthy(
                referenceMs = null,
                fromAmll = false,
                referenceResolved = true,
            ),
        )
    }

    @Test
    fun `resolved reference with no lyric is trustworthy even for AMLL`() {
        // NetEase answered authoritatively and has no lyric for the track, so there is no
        // reference to wait for. Retrying would not produce one.
        assertTrue(
            LyricTimeShift.isAlignmentTrustworthy(
                referenceMs = null,
                fromAmll = true,
                referenceResolved = true,
            ),
        )
    }
}
