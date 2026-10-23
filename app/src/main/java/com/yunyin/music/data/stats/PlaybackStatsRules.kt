package com.yunyin.music.data.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/** One song's accumulated listening, keyed by local track id. */
data class SongStats(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val plays: Int,
    val totalMs: Long,
)

/** One artist/album row in the Top lists. */
data class GroupStats(val key: String, val plays: Int, val totalMs: Long)

/** Day or week bucket for the distribution chart. */
data class StatsBucket(val key: String, val label: String, val totalMs: Long, val plays: Int)

/** Totals shown above the lists. */
data class StatsTotals(val totalMs: Long, val plays: Int, val activeDays: Int)

/** Immutable view of the statistics store, for the page and tests. */
data class PlaybackStatsSnapshot(
    val totals: StatsTotals = StatsTotals(0, 0, 0),
    val songs: List<SongStats> = emptyList(),
    val artists: List<GroupStats> = emptyList(),
    val albums: List<GroupStats> = emptyList(),
    val days: List<StatsBucket> = emptyList(),
    val weeks: List<StatsBucket> = emptyList(),
)

/**
 * Pure playback-statistics rules.
 *
 * ## What counts as a play
 *
 * Only *forward progress while playing* is accumulated. A seek is a jump in position that was not listened to, so any
 * delta larger than [MAX_FORWARD_DELTA_MS] is ignored; seeking backwards and pauses contribute nothing. A play is
 * credited to a track once its session accumulates [MIN_PLAY_MS] of real listening — the project's "real listening
 * accumulation" idea, so a tap that is immediately skipped does not inflate the count.
 */
object PlaybackStatsRules {

    /** A session must accumulate this much real listening before it counts as one play. */
    const val MIN_PLAY_MS = 30_000L

    /** Forward jumps larger than this are seeks, not listening. */
    const val MAX_FORWARD_DELTA_MS = 10_000L

    /** Persist at most this often while playback keeps accumulating. */
    const val FLUSH_INTERVAL_MS = 15_000L

    /** Real listening time in one tick: only forward progress while playing, and never a seek. */
    fun forwardDelta(previousMs: Long, currentMs: Long, playing: Boolean): Long {
        if (!playing) return 0L
        val delta = currentMs - previousMs
        if (delta <= 0L || delta > MAX_FORWARD_DELTA_MS) return 0L
        return delta
    }

    fun countsAsPlay(accumulatedMs: Long): Boolean = accumulatedMs >= MIN_PLAY_MS

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** ISO week (`2026-W43`), so weekly buckets sort like dates. */
    fun weekKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val fields = WeekFields.ISO
        val week = date.get(fields.weekOfWeekBasedYear())
        val year = date.get(fields.weekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    fun weekKeyOfDay(day: String, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching { weekKey(LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli(), zone) }
            .getOrDefault(day)

    fun shouldFlush(dirty: Boolean, nowMs: Long, lastFlushMs: Long, intervalMs: Long = FLUSH_INTERVAL_MS): Boolean =
        dirty && nowMs - lastFlushMs >= intervalMs

    fun topSongs(songs: List<SongStats>, limit: Int = 20): List<SongStats> =
        songs.sortedWith(
            compareByDescending<SongStats> { it.plays }
                .thenByDescending { it.totalMs }
                .thenBy { it.title.lowercase() },
        ).take(limit)

    fun topGroups(groups: Collection<GroupStats>, limit: Int = 20): List<GroupStats> =
        groups.sortedWith(
            compareByDescending<GroupStats> { it.plays }
                .thenByDescending { it.totalMs }
                .thenBy { it.key.lowercase() },
        ).take(limit)

    fun totals(songs: List<SongStats>, days: List<StatsBucket>): StatsTotals =
        StatsTotals(
            totalMs = songs.sumOf { it.totalMs },
            plays = songs.sumOf { it.plays },
            activeDays = days.count { it.totalMs > 0L },
        )

    /** Last [count] day buckets (oldest first), filling missing days with zeros so bars never shift. */
    fun lastDays(
        daily: Map<String, StatsBucket>,
        count: Int = 7,
        today: LocalDate = LocalDate.now(),
    ): List<StatsBucket> = (count - 1 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong()).toString()
        daily[date] ?: StatsBucket(key = date, label = date.takeLast(5), totalMs = 0L, plays = 0)
    }

    /** Normalises bucket heights to 0..1 for the bar chart (zero when nothing is playable). */
    fun barFractions(buckets: List<StatsBucket>): List<Float> {
        val max = buckets.maxOfOrNull { it.totalMs } ?: 0L
        if (max <= 0L) return buckets.map { 0f }
        return buckets.map { (it.totalMs.toFloat() / max).coerceIn(0f, 1f) }
    }

    fun formatDuration(totalMs: Long): String {
        val minutes = (totalMs / 60_000).coerceAtLeast(0)
        val hours = minutes / 60
        return if (hours > 0) "$hours 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
    }
}

/** Debounced persistence policy, kept pure/testable: writes are batched while playback is continuous. */
object StatsFlushRules {
    fun shouldFlush(dirty: Boolean, nowMs: Long, lastFlushMs: Long): Boolean =
        PlaybackStatsRules.shouldFlush(dirty, nowMs, lastFlushMs)
}
