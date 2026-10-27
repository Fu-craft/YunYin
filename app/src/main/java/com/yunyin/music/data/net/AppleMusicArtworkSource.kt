// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64

/**
 * Apple Music's own animated-artwork lookup, ported from LX-Music's
 * `src/renderer/utils/appleDynamicCover` (which in turn credits bunnykek's fetcher).
 *
 * Flow, one request per step:
 *  1. Scrape the `WebPlayKid` JWT out of the music.apple.com bundle (amp-api rejects requests without it).
 *  2. Search the storefronts (CN first, so Chinese albums match) for the album.
 *  3. Ask for `albums/{id}?extend=editorialVideo` and take `motionDetailSquare.video` — the square
 *     animated cover, which is the shape the player needs.
 *
 * The token is short-lived (weeks) and cached; a 401 clears it so the next attempt re-scrapes.
 */
class AppleMusicArtworkSource(
    private val http: OkHttpClient,
    private val diag: (String) -> Unit = {},
) : AnimatedArtworkSource {

    private val gate = Mutex()

    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    override suspend fun search(artist: String, album: String, title: String?): AnimatedArtwork? =
        withContext(Dispatchers.IO) {
            if (artist.isBlank() || album.isBlank()) return@withContext null
            gate.withLock {
                runCatching { lookup(artist, album, title) }
                    .onFailure { diag("stage=animated-artwork apple error reason=${(it.message ?: it.javaClass.simpleName).take(80)}") }
                    .getOrNull()
            }
        }

    private fun lookup(artist: String, album: String, title: String?): AnimatedArtwork? {
        val jwt = token ?: fetchToken()
        val term = listOfNotNull(artist, album).joinToString(" ")

        for (storefront in STOREFRONTS) {
            val albumId = searchAlbum(storefront, term, artist, album) ?: continue
            val motion = editorialVideo(storefront, albumId) ?: continue
            diag("stage=animated-artwork apple hit storefront=$storefront album=$albumId")
            return AnimatedArtwork(
                url = motion.url,
                urlTall = motion.tall,
                artist = artist,
                album = album,
                isCached = false,
                posterUrl = motion.poster,
            )
        }
        diag("stage=animated-artwork apple miss term=${term.take(40)}")
        return null
    }

    // ---------------------------------------------------------------- token

    private fun fetchToken(): String {
        val page = getText(PAGE_URL)
        val asset = Regex("""crossorigin src="(/assets/index.+?\.js)"""")
            .find(page)?.groupValues?.get(1)
            ?: throw IllegalStateException("apple page asset missing")
        val js = getText("https://music.apple.com$asset")
        val candidates = Regex("""ey[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]{2,}""").findAll(js)
        for (match in candidates) {
            val jwt = match.value
            val header = decodeSegment(jwt.substringBefore('.')) ?: continue
            if (header.optString("kid") != "WebPlayKid") continue
            val payload = decodeSegment(jwt.split('.').getOrNull(1) ?: continue) ?: continue
            val expSeconds = payload.optLong("exp", 0L)
            tokenExpiresAt = if (expSeconds > 0) expSeconds * 1000 - 10 * 60 * 1000 else System.currentTimeMillis() + 6 * 3_600_000
            token = jwt
            diag("stage=animated-artwork apple token ok exp=$tokenExpiresAt")
            return jwt
        }
        throw IllegalStateException("WebPlayKid token not found")
    }

    private fun currentToken(): String {
        val now = System.currentTimeMillis()
        val cached = token
        if (cached != null && now < tokenExpiresAt) return cached
        return fetchToken()
    }

    private fun decodeSegment(segment: String): JSONObject? = runCatching {
        val padded = segment.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        JSONObject(String(Base64.getDecoder().decode(padded), Charsets.UTF_8))
    }.getOrNull()

    // ---------------------------------------------------------------- amp-api

    private fun api(url: String, jwt: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $jwt")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                token = null
                tokenExpiresAt = 0
                diag("stage=animated-artwork apple auth status=${response.code}")
                return null
            }
            if (!response.isSuccessful) return null
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun searchAlbum(storefront: String, term: String, artist: String, album: String): String? {
        val encoded = java.net.URLEncoder.encode(term, "UTF-8")
        val json = api("$AMP/v1/catalog/$storefront/search?term=$encoded&types=albums,songs&limit=10", currentToken()) ?: return null
        val results = json.optJSONObject("results") ?: return null
        val albums = results.optJSONObject("albums")?.optJSONArray("data") ?: return null

        var bestId: String? = null
        var bestScore = 0.0
        for (index in 0 until albums.length()) {
            val item = albums.optJSONObject(index) ?: continue
            val attrs = item.optJSONObject("attributes") ?: continue
            val id = item.optString("id").ifBlank { continue }
            val name = attrs.optString("name")
            val artistName = attrs.optString("artistName")
            var score = 0.0
            if (album.isNotBlank() && name.isNotBlank()) {
                score += when {
                    name.equals(album, ignoreCase = true) -> 3.0
                    normalise(name).contains(normalise(album)) || normalise(album).contains(normalise(name)) -> 2.0
                    else -> similarity(normalise(name), normalise(album))
                }
            }
            if (artist.isNotBlank() && artistName.isNotBlank()) {
                score += when {
                    artistName.equals(artist, ignoreCase = true) -> 4.0
                    normalise(artistName).contains(normalise(artist)) -> 3.0
                    else -> similarity(normalise(artistName), normalise(artist)) * 2
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        if (bestScore < 2.0) return null
        return bestId
    }

    private class Motion(val url: String, val tall: String?, val poster: String?)

    private fun editorialVideo(storefront: String, albumId: String): Motion? {
        val json = api("$AMP/v1/catalog/$storefront/albums/$albumId?extend=editorialVideo", currentToken()) ?: return null
        val attributes = json.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: return null
        val editorial = attributes.optJSONObject("editorialVideo") ?: return null
        for (key in MOTION_KEYS) {
            val motion = editorial.optJSONObject(key) ?: continue
            val video = motion.optString("video").takeUnless { it.isBlank() } ?: continue
            val tall = editorial.optJSONObject("motionDetailTall")?.optString("video")?.takeUnless { it.isBlank() }
            // The preview frame is a template URL (`...{w}x{h}...`); ask for the size the cover
            // actually renders at so it is a sharp still, not a thumbnail stretched over the square.
            val poster = motion.optJSONObject("previewFrame")?.optString("url")
                ?.takeUnless { it.isBlank() }
                ?.replace("{w}x{h}", "600x600")
            return Motion(video, tall, poster)
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "status=${response.code} $url" }
            return response.body?.string().orEmpty()
        }
    }

    private fun normalise(value: String): String = value
        .lowercase()
        .replace(Regex("""[（(【\[].*?[）)】\]]"""), "")
        .replace(Regex("""[\u200b\u00ad]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Longest-common-subsequence ratio, as in LX-Music's matcher. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else maxOf(previous[j], current[j - 1])
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous[b.length].toDouble() / maxLen
    }

    private companion object {
        const val AMP = "https://amp-api.music.apple.com"
        const val PAGE_URL = "https://music.apple.com/us/album/positions-deluxe-edition/1553944254"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val STOREFRONTS = listOf("cn", "hk", "tw", "us", "gb", "jp")
        val MOTION_KEYS = listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall")
    }
}
