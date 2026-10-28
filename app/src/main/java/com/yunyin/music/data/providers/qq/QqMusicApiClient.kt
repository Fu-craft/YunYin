// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.qq

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The self-hosted **qq-music-api** (koa2) at `QQ_MUSIC_API_BASE_URL`, used for what the built-in
 * [com.yunyin.music.data.providers.tx.TxSearch] cannot do on its own: a stream URL and a lyric file.
 *
 * The built-in QQ search talks to `u.y.qq.com` directly and returns catalogue metadata only — LX
 * deliberately resolves URLs through a *custom source*, which the user has to install and keep
 * running. This client removes that dependency for QQ: the same self-hosted API answers
 * `/getMusicPlay` (a signed `ws.stream.qqmusic.qq.com` URL) and `/getLyric`.
 *
 * Nothing here assumes the API is configured. [available] is false when the build has no base URL,
 * and every call then returns [NetResult.Err] so callers fall through to their previous path.
 *
 * Endpoint shapes come from <https://sansenjian.github.io/qq-music-api/>:
 * `/getSearchByKey`, `/getMusicPlay`, `/getLyric` — all `GET`, all answering
 * `{ code: 0, msg, data }` on success.
 */
class QqMusicApiClient(
    private val baseUrl: String = BuildConfigBaseUrl,
    private val log: LxSourceLog? = null,
    private val client: OkHttpClient = defaultClient(),
) {

    val available: Boolean = baseUrl.toHttpUrlOrNull() != null

    /** One page of QQ search, shaped like the rest of the app's provider results. */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 20): NetResult<List<Track>> {
        val url = url("getSearchByKey", "key" to keyword, "limit" to limit.toString(), "page" to page.toString())
            ?: return NetResult.Err("QQ API 未配置")
        return request(url) { json ->
            val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                ?: return@request NetResult.Ok(emptyList())
            val tracks = ArrayList<Track>(list.length())
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                trackOf(item)?.let(tracks::add)
            }
            NetResult.Ok(tracks)
        }
    }

    /** A playable URL for [songmid], or null when the API refuses (VIP-only track, region lock). */
    suspend fun musicUrl(songmid: String): String? {
        val cookie = runCatching { com.yunyin.music.BuildConfig.QQ_MUSIC_API_COOKIE }.getOrDefault("")
        val url = url(
            "getMusicPlay",
            "songmid" to songmid,
            "cookie" to cookie,
        ) ?: return null
        return when (val result = request(url) { json ->
            val root = json.optJSONObject("data") ?: json
            val direct = root.optString("url").ifBlank { null }
            val perSong = root.optJSONObject("playUrl")?.let { map ->
                map.keys().asSequence().firstOrNull()?.let { key -> map.optJSONObject(key)?.optString("url") }
            }
            NetResult.Ok(direct ?: perSong)
        }) {
            is NetResult.Ok -> result.value?.takeIf { it.startsWith("http") }
            is NetResult.Err -> null
        }
    }

    /** The lyric file for [songmid]; translation is returned separately by the API when it exists. */
    suspend fun lyric(songmid: String): Lyric? {
        val url = url("getLyric", "songmid" to songmid, "isFormat" to "0") ?: return null
        return when (val result = request(url) { json ->
            val node = json.optJSONObject("lyric") ?: json.optJSONObject("data") ?: return@request NetResult.Ok(null)
            NetResult.Ok(Lyric(lrc = node.optString("lyric"), translation = node.optString("trans").ifBlank { null }))
        }) {
            is NetResult.Ok -> result.value?.takeIf { !it.lrc.isNullOrBlank() }
            is NetResult.Err -> null
        }
    }

    data class Lyric(val lrc: String?, val translation: String?)

    private fun trackOf(item: JSONObject): Track? {
        val mid = item.optString("songmid").ifBlank { item.optString("mid") }
        if (mid.isBlank()) return null
        val name = item.optString("songname").ifBlank { item.optString("title") }.ifBlank { return null }
        val singers = item.optJSONArray("singer")
        val artists = ArrayList<String>()
        if (singers != null) {
            for (i in 0 until singers.length()) {
                singers.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(artists::add)
            }
        }
        val album = item.optJSONObject("album")
        val albumMid = album?.optString("albummid").orEmpty().ifBlank { null }
        val interval = item.optInt("interval", 0)
        val id = item.optString("songid").toLongOrNull() ?: 0L
        val cover = albumMid?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" }
        return Track(
            id = com.yunyin.music.data.providers.ProviderTrackIds.idFor(SOUCE_KEY, mid),
            name = name,
            artists = artists,
            albumName = album?.optString("albumname").orEmpty(),
            albumId = 0L,
            coverUrl = cover,
            durationMs = interval * 1000L,
            providerRef = ProviderRef(
                provider = SOUCE_KEY,
                providerId = mid,
                songId = id.takeIf { it > 0 }?.toString(),
                albumMid = albumMid,
                intervalSeconds = interval,
            ),
        )
    }

    private fun url(path: String, vararg params: Pair<String, String>): String? {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder().addPathSegment(path)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend fun <T> request(url: String, parse: (JSONObject) -> NetResult<T>): NetResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@withContext fail("HTTP ${response.code} $url")
                    val raw = JSONObject(body)
                    // Most answers are wrapped in `response`; /getMusicPlay answers in `data`.
                    // Normalise before reading either one.
                    // normalise before reading either.
                    val json = raw.optJSONObject("response") ?: raw
                    if (json.optInt("code", 0) != 0) {
                        return@withContext fail("code=${json.optInt("code")} msg=${json.optString("msg")}")
                    }
                    parse(json)
                }
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun <T> fail(reason: String): NetResult<T> {
        log?.recordDiagnostic("qq-api stage=request reason=${reason.take(120)}")
        return NetResult.Err(reason)
    }

    companion object {
        /** Same provider key the built-in QQ search uses, so a track's identity is shared. */
        const val SOUCE_KEY = "tx"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/** Build-time configuration; kept separate so the class stays unit-testable with a fake base URL. */
internal val BuildConfigBaseUrl: String
    get() = runCatching { com.yunyin.music.BuildConfig.QQ_MUSIC_API_BASE_URL }.getOrDefault("")
