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
 * 音乐百科 (song wiki) — `/song/wiki/summary`.
 *
 * The endpoint answers a `blocks[]` feed rather than a document. The only block that is stable
 * across songs is `SONG_PLAY_ABOUT_SONG_BASIC`: it carries the entry image and the "参与共建" link,
 * where the full article lives. So this models exactly that: a cover, a title and one link — the
 * card shows it and the reader opens the article in the browser, which is also what the client's own
 * wiki entry point does.
 */
class SongWikiApi(
    private val settings: SettingsStore,
) {

    /** What the card can actually render from the summary feed. */
    data class Wiki(val title: String, val imageUrl: String?, val linkUrl: String?)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun summary(songId: Long): NetResult<Wiki?> = request(
        "/song/wiki/summary",
        mapOf("id" to songId.toString()),
    ).map { json ->
        val blocks = json.optJSONObject("data")?.optJSONArray("blocks") ?: return@map null
        var found: Wiki? = null
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("code") != BASIC_BLOCK) continue
            val element = block.optJSONObject("uiElement") ?: continue
            val title = element.optJSONObject("mainTitle")?.optString("title").orEmpty()
                .ifBlank { "音乐百科" }
            val image = element.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("imageUrl")
                ?.replace("http://", "https://")
                ?.takeIf { it.isNotBlank() }
            val link = element.optJSONArray("textLinks")
                ?.optJSONObject(0)
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
            found = Wiki(title = title, imageUrl = image, linkUrl = link)
            break
        }
        found
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
                            json.optString("message").ifBlank { "百科加载失败（code=$code）" },
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
        const val BASIC_BLOCK = "SONG_PLAY_ABOUT_SONG_BASIC"
    }
}
