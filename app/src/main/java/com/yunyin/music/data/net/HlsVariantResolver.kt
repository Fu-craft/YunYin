// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns an HLS **master** playlist into a single AVC rendition URL.
 *
 * Apple's animated-artwork masters advertise both H.264 and HEVC at the same resolutions. Handing the
 * master straight to ExoPlayer lets its track selector land on an HEVC rendition, and on devices
 * without an HEVC decoder the result is a stream that prepares but never renders a frame — the cover
 * stays static with no error. Picking the rendition ourselves, as LX-Music does, removes that
 * variable.
 */
class HlsVariantResolver(
    private val http: OkHttpClient,
    // The square cover is drawn at up to ~1000 px on a 1080p phone, so a 640-edge rendition is
    // visibly soft. Apple's masters offer AVC up to 1080 for square artwork; take that and let the
    // player scale down, instead of decoding 486 and letting the display stretch it.
    private val maxEdge: Int = 1080,
    private val diag: (String) -> Unit = {},
) {

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 32
    }

    /** Returns a media-playlist URL for [masterUrl], or the original URL when it cannot be narrowed. */
    suspend fun resolve(masterUrl: String): String = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[masterUrl] }?.let {
            diag("stage=animated-artwork variant cache-hit")
            return@withContext it
        }
        val body = runCatching { fetch(masterUrl) }.getOrElse {
            diag("stage=animated-artwork variant error reason=${(it.message ?: it.javaClass.simpleName).take(60)}")
            return@withContext masterUrl
        }
        if (!HlsVariantRules.isMaster(body)) return@withContext masterUrl
        val variants = HlsVariantRules.parseMaster(body)
        val picked = HlsVariantRules.pickBest(variants, maxEdge)
        if (picked == null) {
            diag("stage=animated-artwork variant none variants=${variants.size}")
            return@withContext masterUrl
        }
        val resolved = HlsVariantRules.resolveUri(masterUrl, picked.uri)
        diag("stage=animated-artwork variant pick ${picked.width}x${picked.height} codecs=${picked.codecs.take(24)}")
        synchronized(cache) { cache[masterUrl] = resolved }
        resolved
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HLS master status=${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    }
}
