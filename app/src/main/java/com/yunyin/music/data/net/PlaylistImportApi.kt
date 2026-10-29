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
 * 歌单导入 (`/playlist/import/...`) — turns a list of "song artist" lines or a shared playlist link
 * into a real playlist on the signed-in account.
 *
 * Kept beside [RecentPlayed] rather than inside [NeteaseClient] for the same reason: the main client
 * is huge, and this feature needs nothing from it beyond base URL + cookie.
 *
 * The API is a **two-step job**: creating a task returns an id, and the task is polled for its
 * status. A link import of a large playlist takes seconds, so the caller is expected to poll.
 */
class PlaylistImportApi(
    private val settings: SettingsStore,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** One created import task: the id to poll and the playlist it will fill (when known). */
    data class Task(val id: String, val playlistId: Long?, val message: String?)

    /**
     * Imports free-form text: one song per line, either `歌名 歌手` or just `歌名`.
     *
     * @param playlistName target playlist name; blank lets the server generate one.
     */
    suspend fun createFromText(text: String, playlistName: String? = null): NetResult<Task> =
        create(mapOf("text" to text, "playlistName" to playlistName.orEmpty()))

    /** Imports shared playlist/article links (NetEase, QQ, Kuwo, …) as a new playlist. */
    suspend fun createFromLinks(links: List<String>, playlistName: String? = null): NetResult<Task> {
        val cleaned = links.map { it.trim() }.filter { it.startsWith("http") }
        if (cleaned.isEmpty()) return NetResult.Err("没有可导入的链接")
        val array = JSONArray().apply { cleaned.forEach { put(it) } }
        return create(mapOf("link" to array.toString(), "playlistName" to playlistName.orEmpty()))
    }

    /** Polls one import task. */
    suspend fun status(taskId: String): NetResult<JSONObject> = request(
        "/playlist/import/task/status",
        mapOf("id" to taskId),
    )

    private suspend fun create(query: Map<String, String>): NetResult<Task> =
        request("/playlist/import/name/task/create", query).map { json ->
            val data = json.optJSONObject("data") ?: json
            val id = data.optString("id").ifBlank {
                data.optString("taskId").ifBlank { data.optLong("id").toString() }
            }
            Task(
                id = id,
                playlistId = data.optLong("playlistId").takeIf { it > 0 },
                message = data.optString("message").ifBlank { null },
            )
        }

    private suspend fun request(path: String, query: Map<String, String>): NetResult<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!SettingsStore.hasBaseUrl) return@withContext NetResult.Err("未配置接口地址")
            val url = buildString {
                append(SettingsStore.BASE_URL)
                append(path)
                append('?')
                append(query.entries.joinToString("&") { "${it.key}=${encode(it.value)}" })
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
                        return@withContext NetResult.Err(
                            json.optString("message").ifBlank { "导入失败（code=$code）" },
                        )
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
    }
}
