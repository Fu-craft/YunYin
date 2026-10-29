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
import org.json.JSONObject

/**
 * 最近播放 (cloud) — the `/record/recent/song`, `/album` and `/playlist` endpoints.
 *
 * Lives in its own file rather than as more methods on [NeteaseClient]: that class is large and every
 * attempt to edit it broke the whole compilation unit, so the record feed gets its own small
 * transport instead. The three things it must share with the main client — base URL, cookie, user
 * agent — are read from the same [SettingsStore], so a signed-in session behaves identically.
 *
 * The feed answers *summaries*: `songs` returns ids only (hydrated by the caller through the main
 * client), albums and playlists return ids in play order.
 */
class RecentPlayed(
    private val settings: SettingsStore,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Song ids in play order, most recent first. */
    suspend fun songIds(limit: Int = 100): NetResult<List<Long>> = ids("/record/recent/song", limit)

    /** Album ids in play order. */
    suspend fun albumIds(limit: Int = 100): NetResult<List<Long>> = ids("/record/recent/album", limit)

    /** Playlist ids in play order. */
    suspend fun playlistIds(limit: Int = 100): NetResult<List<Long>> = ids("/record/recent/playlist", limit)

    private suspend fun ids(path: String, limit: Int): NetResult<List<Long>> =
        request(path, mapOf("limit" to limit.toString())).map { json ->
            val list = json.optJSONObject("data")?.optJSONArray("list") ?: return@map emptyList()
            buildList {
                for (index in 0 until list.length()) {
                    val entry = list.optJSONObject(index) ?: continue
                    // The payload is one level down (`data.list[].data`); a bare entry is accepted too
                    // because the API has shipped both shapes.
                    val payload = entry.optJSONObject("data") ?: entry
                    val id = payload.optLong("id").takeIf { it > 0 } ?: continue
                    add(id)
                }
            }
        }

    private suspend fun request(path: String, query: Map<String, String>): NetResult<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!SettingsStore.hasBaseUrl) {
                return@withContext NetResult.Err("未配置接口地址")
            }
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
                        // 301 = 未登录 for this instance; surface that rather than an empty list.
                        val message = json.optString("message").ifBlank { "code=$code" }
                        return@withContext NetResult.Err(message)
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
