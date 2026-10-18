package com.yunyin.music.data.providers.tx

import com.yunyin.music.core.NetResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * QQ Music lyrics: request, decryption and parsing.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0):
 *  - `src/utils/musicSdk/tx/lyric.js` — the `GetPlayLyricInfo` request (module/method/params), the retry-on-code
 *    loop, the numeric-`songID` lookup and the decode→parse pipeline;
 *  - `src/utils/musicSdk/tx/qrcDecode.js` — the QRC decryption, see [QrcDecoder];
 *  - `src/utils/musicSdk/tx/musicInfo.js` — the `get_song_detail_yqq` fallback that maps a `mid` to the numeric id
 *    the lyric call needs (see [songIdFor]).
 *
 * What is faithful: every field of both requests, the `crypt: 1`/`qrc: 1`/`trans: 1`/`roma: 1` flags, the
 * retry budget, and the "decode all three payloads, then parse" order. What is rewritten: Kotlin/OkHttp instead of
 * the reference's React-Native `fetch`, and the decode calls are direct rather than promise-based.
 *
 * ## Failure discipline
 *
 * A failed lyric is not a playback failure: every path returns [NetResult.Err] with a short reason, bounded by the
 * HTTP client's call timeout plus [CALL_TIMEOUT_MS], and the caller (the app's lyric repository) falls back to
 * NetEase/no-lyrics. Nothing hangs.
 */
class TxLyric(
    private val client: OkHttpClient = defaultClient(),
) {

    /** The three encrypted payloads exactly as the endpoint returns them. */
    data class Raw(val lyric: String?, val trans: String?, val roma: String?)

    /** One decoded and parsed set of lyrics. */
    data class Parsed(
        val plain: String?,
        val wordByWord: String?,
        val translation: String?,
        val romanization: String?,
        /** Structured word timing (QRC), used to build karaoke lines without a text round-trip. */
        val lines: List<TxLyricParser.WordLine>,
    ) {
        val isEmpty: Boolean
            get() = plain.isNullOrBlank() && wordByWord.isNullOrBlank()
    }

    /** Fetches, decrypts and parses the lyrics for a numeric QQ song id. */
    suspend fun fetch(songId: Long): NetResult<Parsed> {
        val raw = when (val result = request(songId)) {
            is NetResult.Err -> return result
            is NetResult.Ok -> result.value
        }
        val parsed = TxLyricParser.parse(
            lrc = QrcDecoder.decode(raw.lyric),
            tlrc = QrcDecoder.decode(raw.trans),
            rlrc = QrcDecoder.decode(raw.roma),
        )
        if (parsed.isEmpty) return NetResult.Err("QQ音乐没有可用歌词")
        return NetResult.Ok(
            Parsed(
                plain = parsed.lyric.takeIf { it.isNotBlank() },
                wordByWord = parsed.lxlyric.takeIf { it.isNotBlank() },
                translation = parsed.tlyric.takeIf { it.isNotBlank() },
                romanization = parsed.rlyric.takeIf { it.isNotBlank() },
                lines = parsed.lines,
            ),
        )
    }

    private suspend fun request(songId: Long): NetResult<Raw> {
        var lastFailure = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val json = withTimeout(CALL_TIMEOUT_MS) { TxMusicu.post(client, lyricBody(songId)) }
                val code = json.optInt("code", -1)
                val req = json.optJSONObject("req")
                val reqCode = req?.optInt("code", -1) ?: -1
                if (code != 0 || reqCode != 0) {
                    lastFailure = "code=$code/$reqCode"
                } else {
                    val data = req?.optJSONObject("data")
                    return NetResult.Ok(
                        Raw(
                            lyric = data?.optString("lyric")?.takeIf { it.isNotBlank() },
                            trans = data?.optString("trans")?.takeIf { it.isNotBlank() },
                            roma = data?.optString("roma")?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                lastFailure = "timeout"
            } catch (e: IOException) {
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            } catch (e: Exception) {
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return NetResult.Err("QQ音乐歌词获取失败（$lastFailure）")
    }

    private fun lyricBody(songId: Long): JSONObject = JSONObject()
        .put(
            "comm",
            JSONObject()
                .put("ct", "19")
                .put("cv", "1859")
                .put("uin", "0"),
        )
        .put(
            "req",
            JSONObject()
                .put("method", "GetPlayLyricInfo")
                .put("module", "music.musichallSong.PlayLyricInfo")
                .put(
                    "param",
                    JSONObject()
                        .put("format", "json")
                        .put("crypt", 1)
                        .put("ct", 19)
                        .put("cv", 1873)
                        .put("interval", 0)
                        .put("lrc_t", 0)
                        .put("qrc", 1)
                        .put("qrc_t", 0)
                        .put("roma", 1)
                        .put("roma_t", 0)
                        .put("songID", songId)
                        .put("trans", 1)
                        .put("trans_t", 0)
                        .put("type", -1),
                ),
        )

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val CALL_TIMEOUT_MS = 10_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * The numeric QQ song id for a search result's `mid`.
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0), `src/utils/musicSdk/tx/musicInfo.js` —
 * `music.pf_song_detail_svr/get_song_detail_yqq`. Used only when a persisted [com.yunyin.music.core.ProviderRef]
 * predates the `songId` field (or came from a source that did not carry it): current search results always carry
 * the numeric id, so the common lyric path is one request, not two.
 */
class TxSongId(
    private val client: OkHttpClient = TxLyric.defaultClient(),
) {

    suspend fun songIdFor(songMid: String): Long? {
        if (songMid.isBlank()) return null
        return try {
            val body = JSONObject()
                .put("comm", JSONObject().put("ct", "19").put("cv", "1859").put("uin", "0"))
                .put(
                    "req",
                    JSONObject()
                        .put("module", "music.pf_song_detail_svr")
                        .put("method", "get_song_detail_yqq")
                        .put(
                            "param",
                            JSONObject()
                                .put("song_type", 0)
                                .put("song_mid", songMid),
                        ),
                )
            val json = kotlinx.coroutines.withTimeout(10_000L) { TxMusicu.post(client, body) }
            val track = json.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("track_info")
            track?.optLong("id", 0L)?.takeIf { it > 0L }
        } catch (e: Exception) {
            null
        }
    }
}
