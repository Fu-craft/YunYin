package com.yunyin.music.data.lxsource

import com.yunyin.music.core.Track
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns this app's NetEase track into the `musicInfo` an LX source expects, and picks the quality tier to ask for.
 *
 * ## The shape comes from LX's own NetEase mapping, not from the doc
 *
 * The doc only says `musicInfo` is "音乐信息对象". `src/renderer/utils/musicSdk/wy/musicSearch.js` in
 * lx-music-desktop builds it as `{ songmid: item.id, name, singer, albumName, interval, … }`, so that is the
 * object a `wy` source is written against: `songmid` is the field they read. Numeric `id`/`albumId` and a
 * millisecond `durationMs` are included as harmless aliases.
 *
 * **`hash` is deliberately absent.** A source for another provider does `musicInfo.hash ?? musicInfo.songmid`;
 * with `hash` present-but-empty the `??` keeps the empty string and every request breaks. Absent means the
 * fallback is taken, exactly as on LX.
 *
 * ## Quality bands
 *
 * The app has five tiers (`standard|higher|exhigh|lossless|hires`); LX has four (`128k|320k|flac|flac24bit`).
 * The mapping below is the same one LX's NetEase source uses between its own tiers and the API's level names.
 * A source may declare a narrower set, so [chooseQuality] falls back to the closest supported tier rather than
 * failing a lookup over a tier the source never claimed.
 */
internal object LxMusicInfo {

    /** The LX source key the app's tracks belong to: this app only plays NetEase songs. */
    const val SOURCE_KEY = "wy"

    /** App quality level → LX tier, following LX's NetEase mapping (standard→128k … hires→flac24bit). */
    private val LEVEL_TO_TIER = mapOf(
        "standard" to LxQuality.Q128,
        "higher" to LxQuality.Q320,
        "exhigh" to LxQuality.Q320,
        "lossless" to LxQuality.QFLAC,
        "hires" to LxQuality.QFLAC24,
    )

    private val TIER_RANK = mapOf(
        LxQuality.Q128 to 0,
        LxQuality.Q320 to 1,
        LxQuality.QFLAC to 2,
        LxQuality.QFLAC24 to 3,
    )

    /** The tier to ask for when the source supports it. */
    fun desiredTier(appLevel: String): String = LEVEL_TO_TIER[appLevel] ?: LxQuality.Q320

    /**
     * The tier actually asked for: the desired one when supported, else the closest lower one, else the
     * lowest higher one, else the desired one (a source that declared no usable tiers may still handle it).
     */
    fun chooseQuality(desired: String, supported: List<String>): String {
        if (supported.isEmpty()) return desired
        if (desired in supported) return desired
        val desiredRank = TIER_RANK[desired] ?: 0
        supported
            .filter { (TIER_RANK[it] ?: -1) <= desiredRank }
            .maxByOrNull { TIER_RANK[it] ?: -1 }
            ?.let { return it }
        return supported.minByOrNull { TIER_RANK[it] ?: Int.MAX_VALUE } ?: desired
    }

    /**
     * The `musicInfo` object for [trackId].
     *
     * [track] is nullable because playback resolves a URL from the media item's id on a loading thread, where the
     * full [Track] may not be at hand; the id-only form still carries `songmid`, which is all most sources need.
     */
    fun musicInfo(trackId: Long, track: Track?): JSONObject = JSONObject().apply {
        put("songmid", trackId)
        put("id", trackId)
        put("name", track?.name ?: "")
        put("singer", track?.artistLine ?: "")
        put("albumName", track?.albumName ?: "")
        put("albumId", track?.albumId ?: 0L)
        put("interval", formatInterval(track?.durationMs ?: 0L))
        put("durationMs", track?.durationMs ?: 0L)
    }

    /** `mm:ss`, as LX's NetEase mapping produces from the track duration. */
    fun formatInterval(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSeconds = durationMs / 1000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    /** The full `request` event payload for `musicUrl`. */
    fun musicUrlRequest(trackId: Long, track: Track?, quality: String): String = JSONObject()
        .put("source", SOURCE_KEY)
        .put("action", "musicUrl")
        .put(
            "info",
            JSONObject()
                .put("type", quality)
                .put("musicInfo", musicInfo(trackId, track)),
        )
        .toString()
}

/**
 * The last N tracks seen, so the stream resolver can build a full `musicInfo` from an id alone.
 *
 * `PlaybackService`'s `ResolvingDataSource` runs on ExoPlayer's loading thread and knows only the synthetic
 * `netease://track/<id>` URI; the queue construction path ([TrackMediaItem.mediaItem]) does have each [Track].
 * This is the hand-off between them, bounded so a long session cannot grow it without limit.
 */
object LxTrackMemory {

    private const val MAX_TRACKS = 512
    private val tracks = ConcurrentHashMap<Long, Track>()

    fun remember(track: Track) {
        if (tracks.size > MAX_TRACKS) tracks.clear()
        tracks[track.id] = track
    }

    fun find(id: Long): Track? = tracks[id]
}

/**
 * Reads the URL out of a `musicUrl` result envelope.
 *
 * A source may resolve a plain string (the documented shape) or an object with a `url` field (a common
 * convenience), and either may be wrapped in the bridge's JSON. Only an `http(s)` URL is accepted: anything else
 * would be handed to the player as a data source and fail there, far from the source that caused it.
 */
internal fun parseLxMusicUrl(resultJson: String): String? {
    val value = runCatching {
        if (resultJson.trimStart().startsWith("\"")) {
            JSONObject("{\"url\":$resultJson}").optString("url")
        } else {
            JSONObject(resultJson).optString("url")
        }
    }.getOrNull()?.trim().orEmpty()
    return value.takeIf { url ->
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}
