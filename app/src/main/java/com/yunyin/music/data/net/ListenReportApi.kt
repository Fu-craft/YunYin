// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.map
import com.yunyin.music.data.SettingsStore
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 听歌足迹 — `/listen/data/total` and `/listen/data/report`.
 *
 * Both endpoints require a signed-in session (the instance answers `code 301` otherwise), and the
 * report payload is an undocumented nested structure whose shape has changed between app versions.
 * Rather than binding to field names that may not exist, [Report] keeps the raw JSON and offers
 * tolerant accessors: [totalSeconds] walks the tree for the first duration-shaped number, and
 * [topSongs] finds the first array that looks like songs. The page renders whatever is present.
 */
class ListenReportApi(
    private val settings: SettingsStore,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Total listening time, as seconds, or null when the instance did not put one in the payload. */
    suspend fun total(): NetResult<Long?> = request("/listen/data/total").map { json ->
        json.optJSONObject("data")?.let { totalSeconds(it) }
    }

    /** Week / month / year report. [period] is `week`, `month` or `year`. */
    suspend fun report(period: String): NetResult<Report> = request(
        "/listen/data/report",
        mapOf("type" to period),
    ).map { json -> Report(period = period, raw = json.optJSONObject("data") ?: JSONObject()) }

    /** A parsed-enough report: totals and the top-song rows, with the source kept for future fields. */
    data class Report(val period: String, val raw: JSONObject) {
        val totalSeconds: Long? get() = totalSeconds(raw)

        /** Song rows, best effort: `[id, name, artist]`. */
        val topSongs: List<Row> get() = findSongArray(raw)?.let { array -> 
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val song = item.optJSONObject("song") ?: item
                    val id = song.optLong("id").takeIf { it > 0 } ?: continue
                    val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists") ?: song.optJSONArray("singer")
                    val artist = if (artists != null && artists.length() > 0) {
                        artists.optJSONObject(0)?.optString("name").orEmpty()
                    } else {
                        ""
                    }
                    add(Row(id = id, name = song.optString("name"), artist = artist))
                }
            }
        } ?: emptyList()
    }

    data class Row(val id: Long, val name: String, val artist: String)

    private suspend fun request(path: String, query: Map<String, String> = emptyMap()): NetResult<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!SettingsStore.hasBaseUrl) return@withContext NetResult.Err("未配置接口地址")
            val url = buildString {
                append(SettingsStore.BASE_URL)
                append(path)
                if (query.isNotEmpty()) {
                    append('?')
                    append(query.entries.joinToString("&") { "${it.key}=${encode(it.value)}" })
                }
            }
            try {
                val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                settings.cookie.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                http.newCall(builder.get().build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return@withContext NetResult.Err("HTTP ${response.code}")
                    val code = json.optInt("code", -1)
                    if (code != 200 && code != 0) {
                        val message = json.optString("msg").ifBlank { json.optString("message") }
                        return@withContext NetResult.Err(message.ifBlank { "需要登录（code=$code）" })
                    }
                    NetResult.Ok(json)
                }
            } catch (e: Exception) {
                NetResult.Err(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val USER_AGENT = "YunYin/1.0 (android)"

        /** First number under a key that looks like a duration, anywhere in the tree. */
        fun totalSeconds(node: JSONObject): Long? {
            node.keys().forEach { key ->
                val lower = key.lowercase()
                if (lower.contains("duration") || lower.contains("seconds") || lower.contains("totaltime")) {
                    val value = node.optLong(key, 0L)
                    if (value > 0) return value
                }
            }
            node.keys().forEach { key ->
                val child = node.optJSONObject(key)
                if (child != null) totalSeconds(child)?.let { return it }
            }
            return null
        }

        fun findSongArray(node: JSONObject): JSONArray? {
            node.keys().forEach { key ->
                val array = node.optJSONArray(key) ?: return@forEach
                val first = array.optJSONObject(0) ?: return@forEach
                val song = first.optJSONObject("song") ?: first
                if (song.has("name") && (song.has("ar") || song.has("artists") || song.has("singer"))) return array
            }
            node.keys().forEach { key ->
                node.optJSONObject(key)?.let { child -> findSongArray(child)?.let { return it } }
            }
            return null
        }
    }
}
