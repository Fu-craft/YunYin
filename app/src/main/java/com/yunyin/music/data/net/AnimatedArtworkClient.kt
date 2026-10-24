// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A source of animated-artwork matches for one track. */
interface AnimatedArtworkSource {
    suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork?
}

/**
 * Client for the operator-supplied animated-artwork endpoint:
 *
 * `GET {base}/api/v1/artwork/search?artist=&album=&title=`
 *
 * There is no host default in this file. A blank/invalid base returns null before any socket is
 * opened; the feature is fully inert until the operator configures their own endpoint.
 */
class AnimatedArtworkClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultHttp(),
    private val diag: (String) -> Unit = {},
) : AnimatedArtworkSource {

    val available: Boolean = AnimatedArtworkRules.isAvailable(baseUrl)

    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
        if (!available) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return@runCatching null
                val url = base.newBuilder()
                    .addPathSegments("api/v1/artwork/search")
                    .addQueryParameter("artist", artist)
                    .addQueryParameter("album", album)
                    .addQueryParameter("title", title.orEmpty())
                    .build()
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> {
                            diag("stage=animated-artwork search miss reason=404")
                            null
                        }
                        !response.isSuccessful -> {
                            diag("stage=animated-artwork search error status=${response.code}")
                            null
                        }
                        else -> {
                            val parsed = AnimatedArtworkRules.parse(response.body?.string())
                            if (parsed == null) {
                                diag("stage=animated-artwork search miss reason=parse")
                            } else {
                                diag("stage=animated-artwork search hit")
                            }
                            parsed
                        }
                    }
                }
            }.onFailure {
                diag("stage=animated-artwork search error reason=${(it.message ?: it.javaClass.simpleName).take(80)}")
            }.getOrNull()
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
