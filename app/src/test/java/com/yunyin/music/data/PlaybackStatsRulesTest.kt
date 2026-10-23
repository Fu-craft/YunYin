package com.yunyin.music.data

import com.yunyin.music.data.stats.PlaybackStatsRules
import com.yunyin.music.data.stats.SongStats
import com.yunyin.music.data.stats.StatsBucket
import com.yunyin.music.data.stats.StatsFlushRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure playback-statistics rules: real listening deltas, play threshold, buckets, Top ordering and debounce. */
class PlaybackStatsRulesTest {

    @Test
    fun `only forward progress while playing counts, seeks do not`() {
        assertEquals(1_000L, PlaybackStatsRules.forwardDelta(10_000L, 11_000L, playing = true))
        assertEquals(0L, PlaybackStatsRules.forwardDelta(10_000L, 11_000L, playing = false))
        // A seek forward is far larger than one tick and must not be credited as listening.
        assertEquals(0L, PlaybackStatsRules.forwardDelta(10_000L, 90_000L, playing = true))
        // Seeking backwards contributes nothing either.
        assertEquals(0L, PlaybackStatsRules.forwardDelta(90_000L, 10_000L, playing = true))
    }

    @Test
    fun `a play is credited only after the real-listening threshold`() {
        assertFalse(PlaybackStatsRules.countsAsPlay(29_999L))
        assertTrue(PlaybackStatsRules.countsAsPlay(30_000L))
        assertTrue(PlaybackStatsRules.countsAsPlay(120_000L))
    }

    @Test
    fun `day and ISO week keys are stable for a fixed zone`() {
        val zone = ZoneId.of("UTC")
        val day = LocalDate.of(2025, 12, 18)
        val noon = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2025-12-18", PlaybackStatsRules.dayKey(noon, zone))
        assertTrue(PlaybackStatsRules.weekKey(noon, zone).matches(Regex("""\d{4}-W\d{2}""")))
        assertEquals(
            PlaybackStatsRules.weekKey(noon, zone),
            PlaybackStatsRules.weekKeyOfDay("2025-12-18", zone),
        )
    }

    @Test
    fun `Top songs order by plays then listening time then title`() {
        val songs = listOf(
            SongStats(1, "B", "A", "X", plays = 2, totalMs = 500),
            SongStats(2, "A", "A", "X", plays = 2, totalMs = 1_000),
            SongStats(3, "C", "A", "X", plays = 5, totalMs = 100),
        )
        val top = PlaybackStatsRules.topSongs(songs)
        assertEquals(listOf(3L, 2L, 1L), top.map { it.id })
    }

    @Test
    fun `last seven days fill missing dates and normalise bar heights`() {
        val today = LocalDate.of(2026, 10, 23)
        val buckets = listOf(
            StatsBucket("2026-10-23", "10-23", 2_000L, 2),
            StatsBucket("2026-10-21", "10-21", 1_000L, 1),
        ).associateBy { it.key }
        val days = PlaybackStatsRules.lastDays(buckets, 7, today)
        assertEquals(7, days.size)
        assertEquals("2026-10-23", days.last().key)
        assertEquals(0L, days.first().totalMs)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0.5f, 0f, 1f), PlaybackStatsRules.barFractions(days))
    }

    @Test
    fun `flush policy is debounced and only fires when dirty`() {
        assertFalse(StatsFlushRules.shouldFlush(dirty = false, nowMs = 100_000L, lastFlushMs = 0L))
        assertFalse(StatsFlushRules.shouldFlush(dirty = true, nowMs = 5_000L, lastFlushMs = 0L))
        assertTrue(StatsFlushRules.shouldFlush(dirty = true, nowMs = 20_000L, lastFlushMs = 0L))
    }

    @Test
    fun `duration formatting keeps hours and minutes`() {
        assertEquals("0 分钟", PlaybackStatsRules.formatDuration(30_000L))
        assertEquals("12 分钟", PlaybackStatsRules.formatDuration(12 * 60_000L))
        assertEquals("2 小时 5 分钟", PlaybackStatsRules.formatDuration(125 * 60_000L))
    }
}
