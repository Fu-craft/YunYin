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
 * `GET {base}/api/v1/artwork/search?artist=&album=`
 *
 * The documented contract takes only `artist` and `album`; a `title` query is ignored server-side
 * (verified against the live endpoint: a deliberately wrong title still matches), so it is not sent.
 * A 404 means the album has no animated artwork, not that the request was malformed.
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

    @Suppress("UNUSED_PARAMETER") // `title` is kept on the source interface but is not part of the
    // documented query; the server ignores it, so sending it would only leak a request that looks
    // meaningful while changing nothing.
    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? {
        if (!available) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return@runCatching null
                val url = base.newBuilder()
                    .addPathSegments("api/v1/artwork/search")
                    .addQueryParameter("artist", artist)
                    .addQueryParameter("album", album)
                    .build()
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> {
                            diag("stage=animated-artwork search miss reason=404")
                            null
                        }
                        response.code in 500..599 -> {
                            // The host returns 503 while it is busy. That says nothing about whether the
                            // album has artwork, so it must not be remembered as a miss; the repository
                            // skips caching for this exception and the next play retries.
                            diag("stage=animated-artwork search busy status=${response.code}")
                            throw AnimatedArtworkRetryableException("status=${response.code}")
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
                if (it is AnimatedArtworkRetryableException) throw it
                diag("stage=animated-artwork search error reason=${(it.message ?: it.javaClass.simpleName).take(80)}")
                // A dropped socket, DNS failure or read timeout is equally inconclusive: report it as
                // retryable so it is not cached as "this album has no animated artwork".
                throw AnimatedArtworkRetryableException(it.message ?: it.javaClass.simpleName)
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
