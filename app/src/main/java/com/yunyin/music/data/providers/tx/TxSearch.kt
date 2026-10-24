// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.tx

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.providers.ProviderTrackIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One page of QQ Music search results, in LX's shape (`list`, `total`, `page`, `allPage`). */
data class TxSearchPage(
    val tracks: List<Track>,
    val total: Int,
    val page: Int,
    val allPage: Int,
)

/**
 * QQ Music search — the part of LX's built-in SDK that makes songs outside NetEase's catalogue findable.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0):
 *  - `src/utils/musicSdk/tx/musicSearch.js` — the request body, the desktop search module/method, the
 *    result fields mapped to a track (mid/title/singer/album/interval/file sizes), and paging;
 *  - `src/utils/musicSdk/tx/utils/index.js` — `signRequest` (signed POST to `u.y.qq.com/cgi-bin/musics.fcg`);
 *  - `src/utils/musicSdk/tx/utils/crypto.js` — the `zzc` signature, see [TxSigner];
 *  - `src/utils/musicSdk/kg/songList.js` etc. suggested nothing here; this module only depends on the tx tree.
 *
 * Rewritten as pure Kotlin + OkHttp: the reference used React Native's `fetch` and a native SHA-1 binding; there
 * is no JavaScript engine on this path.
 *
 * ## Why search is the whole port (for this tree)
 *
 * In this LX release the built-in providers do **not** resolve stream URLs: `src/utils/musicSdk/api-source.js`
 * routes every `getMusicUrl` through `apis(source)`, `api-source-info.ts` contains an empty list, and the mobile
 * build therefore relies on a *custom source* for URLs. Search/metadata is inside the SDK; URL resolution is not.
 * This port follows the same split: [TxSearch] searches, the existing custom-source bridge resolves the URL
 * (see `ProviderPlayback`). That is also why complaint 1 is fixable: the NetEase-gated song is found here and its
 * URL is asked of the custom source under the `tx` key.
 *
 * ## Failures
 *
 * Non-2xx, non-JSON, `code != 0` and empty result lists all become [NetResult.Err] with a short message, and the
 * first line is written to the lxsource log so a report can tell "QQ search is down" from "no results".
 */
class TxSearch(
    private val log: LxSourceLog? = null,
    private val client: OkHttpClient = defaultClient(),
    private val random: Random = Random.Default,
) {

    /**
     * One page of QQ Music search results.
     *
     * ## Retries
     *
     * The endpoint answers `code 2001` (not `0`) when it wants the client to back off — observed after several
     * searches in a few seconds. LX retries its search up to five times on any non-success code, and each retry is
     * a **fresh request** (its recursive `musicSearch` rebuilds the body, including the random `searchid`) — so the
     * body and signature are rebuilt per attempt here too. Reusing one signed body across retries looked equivalent
     * but was not: a `searchid` the server has already throttled stays throttled.
     */
    suspend fun search(query: String, page: Int = 1, limit: Int = DEFAULT_LIMIT): NetResult<TxSearchPage> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return NetResult.Ok(TxSearchPage(emptyList(), 0, page, 1))
        var lastFailure = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val body = buildBody(trimmed, page, limit)
                val sign = TxSigner.sign(body)
                val request = Request.Builder()
                    .url("$ENDPOINT?sign=$sign")
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val text = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        response.body?.string() ?: throw IOException("空响应")
                    }
                }
                val pageResult = parseResponse(JSONObject(text), page, limit)
                return NetResult.Ok(pageResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            } catch (e: Exception) {
                lastFailure = "${e.javaClass.simpleName}: ${e.message}"
            }
            if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * attempt)
        }
        recordFailure("QQ音乐搜索失败", "query=$trimmed page=$page attempts=$MAX_ATTEMPTS $lastFailure")
        return NetResult.Err("QQ音乐搜索失败")
    }

    private fun recordFailure(stage: String, detail: String) {
        runCatching { log?.record(stage, detail) }
    }

    // ---------------------------------------------------------------- request

    private fun buildBody(query: String, page: Int, limit: Int): String {
        val comm = JSONObject()
            .put("_channelid", "0")
            .put("_os_version", "6.2.9200-2")
            .put("ct", "19")
            .put("cv", "2151")
            .put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
            .put("patch", "118")
            .put("psrf_access_token_expiresAt", 0)
            .put("psrf_qqaccess_token", "")
            .put("psrf_qqopenid", "")
            .put("psrf_qqunionid", "")
            .put("tmeAppID", "qqmusic")
            .put("tmeLoginType", 0)
            .put("uin", "0")
            .put("wid", "7223299733393904640")
        val param = JSONObject()
            .put("grp", 1)
            .put("num_per_page", limit)
            .put("page_num", page)
            .put("query", query)
            .put("remoteplace", "txt.newclient.top")
            .put("search_type", 0)
            .put("searchid", searchId())
        val service = JSONObject()
            .put("module", "music.search.SearchCgiService")
            .put("method", "DoSearchForQQMusicDesktop")
            .put("param", param)
        return JSONObject()
            .put("comm", comm)
            .put("music.search.SearchCgiService", service)
            .toString()
    }

    /** PC searchid shape: 32 uppercase hex chars + a 5-digit zero-padded number. */
    private fun searchId(): String {
        val guid = buildString(32) {
            repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) }
        }.uppercase(Locale.ROOT)
        return guid + random.nextInt(100_000).toString().padStart(5, '0')
    }

    // ---------------------------------------------------------------- response

    fun parseResponse(json: JSONObject, page: Int, limit: Int): TxSearchPage {
        val service = json.optJSONObject("music.search.SearchCgiService")
            ?: json.optJSONObject("req")
            ?: throw IllegalStateException("no search service in response")
        if (json.optInt("code", -1) != 0 || service.optInt("code", 0) != 0) {
            throw IllegalStateException("code=${json.optInt("code", -1)}/${service.optInt("code", 0)}")
        }
        val data = service.optJSONObject("data") ?: return TxSearchPage(emptyList(), 0, page, 1)
        // The live endpoint nests the song list one level deeper than the older LX code path expects:
        // `data.body.song.list` with totals in `data.meta.sum`. Both shapes are accepted so a server-side change
        // does not silently empty every search.
        val body = data.optJSONObject("body") ?: data
        val songs = body.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until songs.length()) {
                songs.optJSONObject(index)?.let { item -> mapSong(item)?.let { add(it) } }
            }
        }
        val meta = data.optJSONObject("meta")
        val total = meta?.optInt("sum", 0)?.takeIf { it > 0 }
            ?: meta?.optInt("estimate_sum", 0)?.takeIf { it > 0 }
            ?: body.optJSONObject("song")?.optInt("totalnum", tracks.size)
            ?: tracks.size
        val allPage = if (limit > 0) ((total + limit - 1) / limit).coerceAtLeast(1) else 1
        return TxSearchPage(tracks = tracks, total = total, page = page, allPage = allPage)
    }

    private fun mapSong(item: JSONObject): Track? {
        val mid = item.optString("mid").takeIf { it.isNotBlank() } ?: return null
        val file = item.optJSONObject("file")
        val album = item.optJSONObject("album")
        val albumMid = album?.optString("mid")?.takeIf { it.isNotBlank() }
        val intervalSeconds = item.optInt("interval", 0).coerceAtLeast(0)
        val singers = item.optJSONArray("singer")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        val typeSizes = linkedMapOf<String, String>()
        file?.optLong("size_128mp3")?.takeIf { it > 0L }?.let { typeSizes["128k"] = TxFormat.sizeFormate(it) }
        file?.optLong("size_320mp3")?.takeIf { it > 0L }?.let { typeSizes["320k"] = TxFormat.sizeFormate(it) }
        file?.optLong("size_flac")?.takeIf { it > 0L }?.let { typeSizes["flac"] = TxFormat.sizeFormate(it) }
        file?.optLong("size_hires")?.takeIf { it > 0L }?.let { typeSizes["flac24bit"] = TxFormat.sizeFormate(it) }
        val qualities = typeSizes.keys.toList()
        val cover = when {
            albumMid != null -> "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            else -> item.optJSONArray("singer")?.optJSONObject(0)?.optString("mid")
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://y.gtimg.cn/music/photo_new/T001R500x500M000$it.jpg" }
        }
        return Track(
            id = ProviderTrackIds.idFor(PROVIDER, mid),
            name = item.optString("title").ifBlank { item.optString("name") },
            artists = singers,
            albumName = album?.optString("name").orEmpty(),
            albumId = 0L,
            coverUrl = cover,
            durationMs = intervalSeconds * 1000L,
            providerRef = ProviderRef(
                provider = PROVIDER,
                providerId = mid,
                // The lyric API takes the numeric id; the search response carries it alongside the mid.
                songId = item.optLong("id", 0L).takeIf { it > 0L }?.toString(),
                mediaMid = file?.optString("media_mid")?.takeIf { it.isNotBlank() },
                albumMid = albumMid,
                intervalSeconds = intervalSeconds,
                qualities = qualities,
                typeSizes = typeSizes,
            ),
        )
    }

    companion object {
        const val PROVIDER = "tx"
        const val DEFAULT_LIMIT = 30
        const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musics.fcg"
        const val USER_AGENT = "QQMusic 14090508(android 12)"

        /** LX retries up to five times before giving up; keep the same budget and a growing backoff. */
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 600L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
