package com.yunyin.music.data.net

import com.yunyin.music.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * 心动模式 (`/playmode/intelligence/list`) id extraction and hydration rules.
 *
 * ## Why the payload is only an id source
 *
 * The endpoint's song objects turned out **not** to use the `/song/detail` field names (`ar`/`al`/`dt`/`name`):
 * playback and lyrics worked because only the id is needed, but every row showed no cover, no title and no
 * artist. The success payload cannot be probed here (the deployed instance needs a logged-in cookie), so rather
 * than guessing another shape the queue is built in two shape-independent steps:
 *
 *  1. extract the **ordered ids** from the payload (any of the documented array locations, deduplicated,
 *     non-positive dropped);
 *  2. resolve those ids through the authoritative batched `/song/detail?ids=` endpoint the playlist loader
 *     already uses, then rebuild the list in id order.
 *
 * That makes the display fields correct by construction, and it means the only thing this file has to know
 * about the beat-mode payload is where its ids are.
 *
 * ## Invariant
 *
 * A queue row must never be id-only: [isDisplayable] requires a non-blank title and at least one non-blank
 * artist name, and [queueFor]/[hydrate] refuse rows that do not satisfy it. The host never installs a queue
 * that fails that check.
 *
 * Everything here is pure and unit-tested ([BeatModeRulesTest]).
 */
object BeatModeRules {

    /** Shown immediately for a guest (no cookie) and returned by the client before any request. */
    const val GUEST_MESSAGE = "请先登录"

    /** Shown when the stored cookie no longer authenticates (code 301/401 on this instance). */
    const val EXPIRED_MESSAGE = "登录已过期，请重新登录"

    /** The beat-mode payload had no usable ids. */
    const val EMPTY_MESSAGE = "没有获取到心动模式歌曲，请稍后重试"

    /** The id list existed but `/song/detail` produced no displayable track. */
    const val HYDRATION_MESSAGE = "心动模式歌曲信息获取失败"

    /** The player's own failed-start message when nothing is playing. */
    const val NO_TRACK_MESSAGE = "请先播放一首歌曲"

    /** Array keys that have carried the recommendation across module versions, most specific first. */
    private val LIST_KEYS = listOf("recommend", "songs", "songList", "list", "tracks", "data")

    /**
     * The song objects in the payload, de-duplicated by id and preserving order.
     *
     * Only their ids are used downstream (see the class note); this stays available so the shape handling can
     * be unit-tested directly.
     */
    fun songObjects(json: JSONObject): List<JSONObject> {
        val arrays = ArrayList<JSONArray>()
        json.optJSONObject("data")?.let { data ->
            LIST_KEYS.forEach { key -> data.optJSONArray(key)?.let { arrays += it } }
        }
        LIST_KEYS.forEach { key -> json.optJSONArray(key)?.let { arrays += it } }

        // An empty `recommend` must not stop a populated `songs` from being read on a build that
        // returns both.
        val raw = arrays.firstOrNull { it.length() > 0 } ?: return emptyList()
        val seen = HashSet<Long>(raw.length())
        val out = ArrayList<JSONObject>(raw.length())
        for (i in 0 until raw.length()) {
            val entry = raw.optJSONObject(i) ?: continue
            val song = entry.optJSONObject("song") ?: entry.optJSONObject("track") ?: entry
            val id = song.optLong("id")
            if (id > 0L && seen.add(id)) out += song
        }
        return out
    }

    /**
     * The ordered, de-duplicated, positive ids of the beat-mode payload.
     *
     * This is the **only** thing taken from that response; display metadata comes from `/song/detail` by id.
     */
    fun songIds(json: JSONObject): List<Long> =
        songObjects(json).mapNotNull { song -> song.optLong("id").takeIf { it > 0L } }

    /** A row is installable only with a title and at least one artist name — never an id-only row. */
    fun isDisplayable(track: Track): Boolean =
        track.name.isNotBlank() && track.artists.any { it.isNotBlank() }

    /** Outcome of hydrating the id list: either an ordered queue, or a specific failure. */
    sealed interface Hydration {
        /** [tracks] preserves [songIds] order; [droppedIds] are the ids the detail endpoint could not describe. */
        data class Ready(val tracks: List<Track>, val droppedIds: List<Long>) : Hydration

        /** Nothing displayable could be resolved — the host reports [HYDRATION_MESSAGE]. */
        data object Failed : Hydration
    }

    /**
     * Rebuilds the queue in the **original id order** from the detail endpoint's results.
     *
     * - ids the detail endpoint did not return (deleted/unavailable) are dropped, without shifting the rest;
     * - ids whose returned track has no title/artist are dropped too ([isDisplayable]) — the id-only-row guard;
     * - a duplicate id is only installed once;
     * - no displayable track at all → [Hydration.Failed], so the host can fail with a specific message instead
     *   of installing an empty or blank queue.
     */
    fun hydrate(ids: List<Long>, tracksById: Map<Long, Track>): Hydration {
        val dropped = ArrayList<Long>()
        val out = ArrayList<Track>(ids.size)
        val seen = HashSet<Long>(ids.size)
        ids.forEach { id ->
            if (!seen.add(id)) return@forEach
            val track = tracksById[id]
            if (track == null || !isDisplayable(track)) {
                dropped += id
            } else {
                out += track
            }
        }
        return if (out.isEmpty()) Hydration.Failed else Hydration.Ready(out, dropped)
    }

    /**
     * The queue to install: the hydrated recommendation, with [startTrack] prepended when the server did not
     * include it, so playback can always start on the song the user invoked 心动模式 from.
     *
     * Rows that fail [isDisplayable] are never installed; an empty result means the host must leave the
     * current queue untouched and show [HYDRATION_MESSAGE].
     */
    fun queueFor(recommended: List<Track>, startTrack: Track?, startSongId: Long): List<Track> {
        // No hydrated recommendation means hydration failed; the host must not install a seed-only queue
        // under the name 心动模式 (it would silently not be the recommended list).
        if (recommended.isEmpty()) return emptyList()
        val seen = HashSet<Long>(recommended.size + 1)
        val out = ArrayList<Track>(recommended.size + 1)
        recommended.forEach { track ->
            if (isDisplayable(track) && seen.add(track.id)) out += track
        }
        if (
            out.none { it.id == startSongId } &&
            startTrack != null &&
            startSongId > 0L &&
            isDisplayable(startTrack)
        ) {
            out.add(0, startTrack)
        }
        return out
    }

    /** Index of the song to start on; 0 when it is absent (the queue is never empty at call sites). */
    fun startIndex(queue: List<Track>, startSongId: Long): Int =
        queue.indexOfFirst { it.id == startSongId }.coerceAtLeast(0)

    /**
     * The `pid` to send: the playlist the caller knows (collection page) wins, then the playlist the
     * current queue was started from, then the account's liked-songs playlist.
     *
     * Null means "omit the parameter" — a guest or a queue that came from nowhere playlist-shaped.
     * The server's answer is what the UI reports in that case.
     */
    fun resolvePlaylistId(
        explicitPlaylistId: Long?,
        lastPlaylistId: Long?,
        likedPlaylistId: Long?,
    ): Long? = sequenceOf(explicitPlaylistId, lastPlaylistId, likedPlaylistId)
        .firstOrNull { it != null && it > 0L }

    /**
     * Maps a failed request's message to one of the two login messages, or null when it is not a
     * login failure (so the server's own message is reported unchanged).
     *
     * The instance answers the need-login case with a 301 body whose `msg` is "需要登录"; the client
     * also turns a non-JSON 301/401 into "HTTP 301"/"HTTP 401", both handled here. This works on the
     * message text because `request` collapses the status code into the message by design.
     */
    fun loginFailureMessage(rawMessage: String?): String? {
        val text = rawMessage?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when {
            text.contains("301") || text.contains("需要登录") -> GUEST_MESSAGE
            text.contains("401") || text.contains("过期") || text.contains("未登录") -> EXPIRED_MESSAGE
            else -> null
        }
    }
}
