package com.yunyin.music.data.stats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local, vendor-independent playback statistics.
 *
 * Stored as a small JSON document in the app's private files directory: totals, per-day buckets and per-song /
 * artist / album aggregates. All mutations and reads are synchronized; [snapshot] is immutable for Compose.
 */
class PlaybackStatsStore(context: Context) {
    private val file = File(context.filesDir, "playback-stats.json")
    private val lock = Any()
    private var totalMs = 0L
    private var plays = 0
    /** day -> [listened ms, plays] */
    private val days = HashMap<String, LongArray>()
    private val songs = HashMap<Long, SongAgg>()
    private val artists = HashMap<String, Agg>()
    private val albums = HashMap<String, Agg>()
    private var loaded = false
    private var dirty = false
    private var lastFlushMs = 0L

    private data class SongAgg(
        val title: String,
        val artist: String,
        val album: String,
        var plays: Int = 0,
        var ms: Long = 0L,
    )

    private class Agg(var plays: Int = 0, var ms: Long = 0L)


    /** Reads and parses the JSON. Never call from composition: AppContainer runs this on IO. */
    fun loadNow() {
        synchronized(lock) { load() }
    }

    fun snapshot(): PlaybackStatsSnapshot = synchronized(lock) { runCatching { snapshotUnsafe() }.getOrElse { PlaybackStatsSnapshot() } }

    private fun snapshotUnsafe(): PlaybackStatsSnapshot = synchronized(lock) {
        val songRows = songs.map { (id, value) ->
            SongStats(id, value.title, value.artist, value.album, value.plays, value.ms)
        }
        val artistRows = artists.map { (key, value) -> GroupStats(key, value.plays, value.ms) }
        val albumRows = albums.map { (key, value) -> GroupStats(key, value.plays, value.ms) }
        val dayRows = days.map { (key, value) ->
            StatsBucket(key = key, label = key.takeLast(5), totalMs = value[0], plays = value[1].toInt())
        }.sortedBy { it.key }
        val weekRows = dayRows.groupBy { PlaybackStatsRules.weekKeyOfDay(it.key) }
            .map { (key, rows) -> StatsBucket(key, key, rows.sumOf { it.totalMs }, rows.sumOf { it.plays }) }
            .sortedBy { it.key }
        PlaybackStatsSnapshot(
            loaded = loaded,
            totals = PlaybackStatsRules.totals(songRows, dayRows),
            songs = PlaybackStatsRules.topSongs(songRows, 20),
            artists = PlaybackStatsRules.topGroups(artistRows, 20),
            albums = PlaybackStatsRules.topGroups(albumRows, 20),
            days = PlaybackStatsRules.lastDays(dayRows.associateBy { it.key }, 7),
            weeks = weekRows,
        )
    }

    /** Adds real listening time to the current track / artist / album / day. */
    fun addListening(trackId: Long, title: String, artist: String, album: String, deltaMs: Long, dayKey: String) {
        if (deltaMs <= 0L) return
        synchronized(lock) {
            totalMs += deltaMs
            days.getOrPut(dayKey) { longArrayOf(0L, 0L) }[0] += deltaMs
            songs.getOrPut(trackId) { SongAgg(title, artist, album) }.let { it.ms += deltaMs }
            artists.getOrPut(artist.ifBlank { UNKNOWN_ARTIST }) { Agg() }.ms += deltaMs
            albums.getOrPut(album.ifBlank { UNKNOWN_ALBUM }) { Agg() }.ms += deltaMs
            dirty = true
        }
    }

    /** Credits one play to the track / artist / album / day. */
    fun addPlay(trackId: Long, title: String, artist: String, album: String, dayKey: String) {
        synchronized(lock) {
            plays += 1
            days.getOrPut(dayKey) { longArrayOf(0L, 0L) }[1] += 1
            songs.getOrPut(trackId) { SongAgg(title, artist, album) }.plays += 1
            artists.getOrPut(artist.ifBlank { UNKNOWN_ARTIST }) { Agg() }.plays += 1
            albums.getOrPut(album.ifBlank { UNKNOWN_ALBUM }) { Agg() }.plays += 1
            dirty = true
        }
    }

    /** Removes the persisted statistics; used by the error state's "清空统计数据". */
    fun clear() {
        synchronized(lock) {
            totalMs = 0L; plays = 0
            days.clear(); songs.clear(); artists.clear(); albums.clear()
            dirty = false; lastFlushMs = 0L
            runCatching { file.delete() }
        }
    }

    /** Writes when dirty and the debounce interval has elapsed (or [force]). Returns whether it wrote. */
    fun flushIfDue(nowMs: Long, force: Boolean = false): Boolean = synchronized(lock) {
        if (!dirty) return false
        if (!force && !StatsFlushRules.shouldFlush(true, nowMs, lastFlushMs)) return false
        runCatching { save() }.onSuccess {
            dirty = false
            lastFlushMs = nowMs
        }
        !dirty
    }

    private fun load() {
        if (loaded) return
        runCatching {
            if (!file.isFile) return
            val root = JSONObject(file.readText())
            totalMs = root.optLong("totalMs").coerceAtLeast(0L)
            plays = root.optInt("plays").coerceAtLeast(0)
            root.optJSONObject("days")?.let { obj ->
                obj.keys().forEach { key ->
                    val row = obj.optJSONArray(key) ?: return@forEach
                    days[key.takeIf { it.isNotBlank() } ?: return@forEach] = longArrayOf(row.optLong(0).coerceAtLeast(0L), row.optLong(1).coerceAtLeast(0L))
                }
            }
            root.optJSONObject("songs")?.let { obj ->
                obj.keys().forEach { key ->
                    val row = obj.optJSONObject(key) ?: return@forEach
                    songs[key.toLongOrNull() ?: return@forEach] = SongAgg(
                        title = row.optString("title"),
                        artist = row.optString("artist"),
                        album = row.optString("album"),
                        plays = row.optInt("plays").coerceAtLeast(0),
                        ms = row.optLong("ms").coerceAtLeast(0L),
                    )
                }
            }
            root.optJSONObject("artists")?.let { obj ->
                obj.keys().forEach { key ->
                    val row = obj.optJSONArray(key) ?: return@forEach
                    artists[key] = Agg(row.optInt(0).coerceAtLeast(0), row.optLong(1).coerceAtLeast(0L))
                }
            }
            root.optJSONObject("albums")?.let { obj ->
                obj.keys().forEach { key ->
                    val row = obj.optJSONArray(key) ?: return@forEach
                    albums[key] = Agg(row.optInt(0).coerceAtLeast(0), row.optLong(1).coerceAtLeast(0L))
                }
            }
        }
        loaded = true
    }

    private fun save() {
        val root = JSONObject()
        root.put("totalMs", totalMs)
        root.put("plays", plays)
        val dayObj = JSONObject()
        days.forEach { (key, row) -> dayObj.put(key, JSONArray().put(row[0]).put(row[1])) }
        root.put("days", dayObj)
        val songObj = JSONObject()
        songs.forEach { (id, value) ->
            songObj.put(id.toString(), JSONObject().apply {
                put("title", value.title)
                put("artist", value.artist)
                put("album", value.album)
                put("plays", value.plays)
                put("ms", value.ms)
            })
        }
        root.put("songs", songObj)
        val artistObj = JSONObject()
        artists.forEach { (key, value) -> artistObj.put(key, JSONArray().put(value.plays).put(value.ms)) }
        root.put("artists", artistObj)
        val albumObj = JSONObject()
        albums.forEach { (key, value) -> albumObj.put(key, JSONArray().put(value.plays).put(value.ms)) }
        root.put("albums", albumObj)
        file.writeText(root.toString())
    }

    private companion object {
        const val UNKNOWN_ARTIST = "未知艺人"
        const val UNKNOWN_ALBUM = "未知专辑"
    }
}

/**
 * Turns the player's position ticks into real listening time and plays.
 *
 * Fed from a single slow tick (about once per second), not per frame: statistics do not need frame precision, and the
 * forward-delta rule is what filters seeks out. A session is one continuous track; changing tracks finalizes it. A
 * play is credited once, as soon as the session crosses [PlaybackStatsRules.MIN_PLAY_MS].
 */
class PlaybackStatsRecorder(private val store: PlaybackStatsStore) {
    private var sessionId: Long = 0L
    private var lastPositionMs: Long = 0L
    private var accumulatedMs: Long = 0L
    private var counted = false
    private var lastFlushAt = 0L

    private val _state = MutableStateFlow(store.snapshot())
    val state: StateFlow<PlaybackStatsSnapshot> = _state.asStateFlow()

    val snapshot: PlaybackStatsSnapshot
        get() = store.snapshot()

    fun tick(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        playing: Boolean,
        nowMs: Long,
    ) {
        if (trackId != sessionId) {
            sessionId = trackId
            lastPositionMs = positionMs
            accumulatedMs = 0L
            counted = false
            if (trackId <= 0L) return
        }
        val delta = PlaybackStatsRules.forwardDelta(lastPositionMs, positionMs, playing)
        lastPositionMs = positionMs
        if (delta > 0L) {
            accumulatedMs += delta
            store.addListening(trackId, title, artist, album, delta, PlaybackStatsRules.dayKey(nowMs))
        }
        if (!counted && PlaybackStatsRules.countsAsPlay(accumulatedMs)) {
            counted = true
            store.addPlay(trackId, title, artist, album, PlaybackStatsRules.dayKey(nowMs))
        }
        if (store.flushIfDue(nowMs)) lastFlushAt = nowMs
        _state.value = store.snapshot()
    }

    fun flush(nowMs: Long) {
        store.flushIfDue(nowMs, force = true)
        lastFlushAt = nowMs
        _state.value = store.snapshot()
    }

    /** Loads the persisted document off the main thread; idempotent. */
    suspend fun loadNow() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.loadNow() }
        _state.value = store.snapshot()
    }

    /** Clears persisted statistics after a corrupt/illegible file was detected. */
    fun clear() {
        store.clear()
        _state.value = store.snapshot()
    }
}
