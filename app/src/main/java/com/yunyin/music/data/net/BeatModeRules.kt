package com.yunyin.music.data.net

import com.yunyin.music.core.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * 心动模式 (`/playmode/intelligence/list`) payload parsing and the queue/`pid` rules around it.
 *
 * The route is verified to exist on the deployed instance and to be login-gated: a raw probe answered
 * `HTTP 301 {"code":301,"message":null,"msg":"需要登录"}` with no `Location` header — NetEase's
 * "need login" code surfaced as the HTTP status, not a redirect — and missing `pid` is an HTTP 500.
 * A success payload could not be probed without a real account, so the documented response shapes are
 * accepted rather than assumed:
 *
 *  - the recommended list may sit under `data.recommend` (the module's documented shape), under
 *    `data.songs` / `data.list` (other builds), or at the top level;
 *  - each element may be a song object itself or a wrapper `{ "song": {...} }` / `{ "track": {...} }`;
 *  - entries without a positive id are dropped, like [ArtistPayloads] does for artists/albums.
 *
 * Everything here is pure and unit-tested ([BeatModeRulesTest]) so a wrong guess fails in a test
 * instead of silently producing an empty or wrong queue in the UI.
 */
object BeatModeRules {

    /** Shown immediately for a guest (no cookie) and returned by the client before any request. */
    const val GUEST_MESSAGE = "请先登录"

    /** Shown when the stored cookie no longer authenticates (code 301/401 on this instance). */
    const val EXPIRED_MESSAGE = "登录已过期，请重新登录"

    /** Success payload had no usable songs. Kept distinct from a network/API failure. */
    const val EMPTY_MESSAGE = "没有获取到心动模式歌曲，请稍后重试"

    /** The player's own failed-start message when nothing is playing. */
    const val NO_TRACK_MESSAGE = "请先播放一首歌曲"

    /** Array keys that have carried the recommendation across module versions, most specific first. */
    private val LIST_KEYS = listOf("recommend", "songs", "songList", "list", "tracks", "data")

    /**
     * The recommended songs in the payload, de-duplicated by id and preserving order.
     *
     * Returns an empty list when no shape matches; callers treat that as "server returned nothing
     * usable", never as success with an empty queue.
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
     * The queue to install: the recommendation, with [startTrack] prepended when the server did not
     * include it, so playback can always start on the song the user invoked 心动模式 from.
     *
     * Empty recommendation → empty queue (the host reports [EMPTY_MESSAGE] and leaves the current
     * queue untouched).
     */
    fun queueFor(recommended: List<Track>, startTrack: Track?, startSongId: Long): List<Track> {
        if (recommended.isEmpty()) return emptyList()
        val seen = HashSet<Long>(recommended.size + 1)
        val out = ArrayList<Track>(recommended.size + 1)
        recommended.forEach { track -> if (seen.add(track.id)) out += track }
        if (startTrack != null && out.none { it.id == startSongId }) {
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
