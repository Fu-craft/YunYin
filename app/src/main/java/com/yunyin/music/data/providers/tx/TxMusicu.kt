// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.tx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * The `musicu.fcg` gateway QQ Music uses for lyric and song-detail calls.
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0): the endpoint, headers and body shapes come from
 * `src/utils/musicSdk/tx/lyric.js` and `src/utils/musicSdk/tx/musicInfo.js`. Those files use the app's generic
 * `httpFetch`; this port has no React Native layer, so the shared POST is factored out here.
 */
internal object TxMusicu {

    const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    /** The browser UA the reference sends for lyric/detail requests (different from the search UA). */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun post(client: OkHttpClient, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Referer", "https://y.qq.com")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("空响应")
            }
        }
        return JSONObject(text)
    }
}
