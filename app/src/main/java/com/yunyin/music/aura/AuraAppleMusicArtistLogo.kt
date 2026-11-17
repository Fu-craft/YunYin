// SPDX-License-Identifier: AGPL-3.0-or-later
package com.yunyin.music.aura

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Raw metadata extracted from an Apple Music artist detail header without account or token.
 */
internal data class AppleMusicArtistAssets(
    val logoUrl: String? = null,
    val artworkUrl: String? = null,
    val motionMasterUrl: String? = null,
)

/**
 * Resolved UI-ready artist media URLs and local remuxed motion video path.
 */
data class ResolvedArtistMedia(
    val logoUrl: String? = null,
    val artworkUrl: String? = null,
    val motionVideoPath: String? = null,
)

/**
 * Resolves accountless, tokenless Apple Music artist media (`artistLogo`, `artwork`, and
 * `motionArtistSquare1x1` / `motionArtistFullscreen16x9` video) from public iTunes artist search
 * and desktop storefront artist page HTML (`serialized-server-data`).
 */
object AppleMusicArtistLogoManager {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val STOREFRONTS = listOf("cn", "us")
    private const val MAX_CANDIDATES = 3
    internal const val DEFAULT_MAX_WIDTH_PX = 1200
    internal const val DEFAULT_MAX_ARTWORK_PX = 1200
    private const val NONE_TTL_MS = 7L * 24 * 3600 * 1000
    private const val FAIL_TTL_MS = 30L * 60 * 1000
    // v2 invalidates FAIL/NONE entries written before the Apple Music artist source was wired correctly.
    private const val DIR_NAME = "am_artist_assets_v2"
    private const val INDEX_NAME = "index.jsonl"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private data class CachedEntry(
        val status: String,
        val assets: AppleMusicArtistAssets,
        val timestampMs: Long,
    )

    private val indexMutex = Mutex()
    private val videoMutex = Mutex()
    private val indexCache = ConcurrentHashMap<String, CachedEntry>()
    @Volatile
    private var indexLoaded = false

    private val inflightMutex = Mutex()
    private val inflightAssets = HashMap<String, CompletableDeferred<AppleMusicArtistAssets?>>()

    fun peekCachedArtistMotionPath(context: Context, artistName: String): String? {
        val key = normalizeArtistKey(artistName) ?: return null
        val file = motionCacheFile(context.applicationContext, key)
        return file.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    suspend fun resolveArtistLogoUrl(context: Context, artistName: String): String? =
        resolveArtistMedia(
            context = context,
            artistName = artistName,
            needLogo = true,
            needArtwork = false,
            needMotionVideo = false,
        ).logoUrl

    suspend fun resolveArtistMedia(
        context: Context,
        artistName: String,
        needLogo: Boolean,
        needArtwork: Boolean,
        needMotionVideo: Boolean,
        activePlayingVideoPath: String? = null,
    ): ResolvedArtistMedia {
        if (!needLogo && !needArtwork && !needMotionVideo) return ResolvedArtistMedia()
        val candidates = artistNameCandidates(artistName)
        if (candidates.isEmpty()) return ResolvedArtistMedia()

        val appContext = context.applicationContext
        val resolved = candidates.firstNotNullOfOrNull { cleaned ->
            val key = AppleMusicMotionCoverManager.norm(cleaned)
            if (key.isEmpty()) {
                null
            } else {
                resolveAssetsSingleflight(appContext, cleaned, key)?.let { key to it }
            }
        } ?: return ResolvedArtistMedia()
        val (key, assets) = resolved

        val motionPath = if (needMotionVideo && !assets.motionMasterUrl.isNullOrBlank()) {
            ensureMotionVideoFile(
                context = appContext,
                key = key,
                masterUrl = assets.motionMasterUrl,
                activePlayingPath = activePlayingVideoPath,
            )
        } else {
            null
        }

        return ResolvedArtistMedia(
            logoUrl = if (needLogo) assets.logoUrl else null,
            artworkUrl = if (needArtwork) assets.artworkUrl else null,
            motionVideoPath = motionPath,
        )
    }

    private suspend fun resolveAssetsSingleflight(
        appContext: Context,
        cleanedArtist: String,
        key: String,
    ): AppleMusicArtistAssets? {
        ensureIndexLoaded(appContext)
        indexCache[key]?.let { entry ->
            when (entry.status) {
                "OK" -> return entry.assets
                "NONE" -> if (System.currentTimeMillis() - entry.timestampMs < NONE_TTL_MS) return null
                "FAIL" -> if (System.currentTimeMillis() - entry.timestampMs < FAIL_TTL_MS) return null
            }
        }

        val (deferred, owner) = inflightMutex.withLock {
            val existing = inflightAssets[key]
            if (existing != null && existing.isActive) {
                existing to false
            } else {
                CompletableDeferred<AppleMusicArtistAssets?>().also { inflightAssets[key] = it } to true
            }
        }
        if (!owner) return deferred.await()

        return try {
            val resolved = withContext(Dispatchers.IO) {
                fetchArtistAssets(appContext, cleanedArtist, key)
            }
            deferred.complete(resolved)
            resolved
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        } finally {
            inflightMutex.withLock {
                if (inflightAssets[key] === deferred) inflightAssets.remove(key)
            }
        }
    }

    private suspend fun fetchArtistAssets(
        context: Context,
        artistName: String,
        key: String,
    ): AppleMusicArtistAssets? {
        var sawRetryableFailure = false
        for (storefront in STOREFRONTS) {
            currentCoroutineContext().ensureActive()
            val encoded = URLEncoder.encode(artistName, "UTF-8")
            val searchUrl = "https://itunes.apple.com/search?term=$encoded&entity=musicArtist&country=$storefront&limit=5"
            val searchJson = try {
                getString(searchUrl, acceptLanguage(storefront))
            } catch (_: IOException) {
                sawRetryableFailure = true
                null
            } ?: continue

            val candidates = extractArtistLinkCandidates(searchJson, artistName)
            for (artistUrl in candidates) {
                currentCoroutineContext().ensureActive()
                val html = try {
                    getString(artistUrl, acceptLanguage(storefront))
                } catch (_: IOException) {
                    sawRetryableFailure = true
                    null
                } ?: continue

                val sections = AppleMusicMotionCoverManager.extractSectionsJson(html) ?: continue
                val assets = extractArtistAssets(sections) ?: continue
                putIndexEntry(context, key, "OK", assets)
                return assets
            }
        }
        putIndexEntry(context, key, if (sawRetryableFailure) "FAIL" else "NONE", AppleMusicArtistAssets())
        return null
    }

    private suspend fun ensureMotionVideoFile(
        context: Context,
        key: String,
        masterUrl: String,
        activePlayingPath: String?,
    ): String? = withContext(Dispatchers.IO) {
        val outFile = motionCacheFile(context, key)
        if (outFile.isFile && outFile.length() > 0L) {
            outFile.setLastModified(System.currentTimeMillis())
            return@withContext outFile.absolutePath
        }
        videoMutex.withLock {
            if (outFile.isFile && outFile.length() > 0L) {
                outFile.setLastModified(System.currentTimeMillis())
                return@withLock outFile.absolutePath
            }
            val dir = AppleMusicMotionCoverManager.cacheDir(context)
            val ok = runCatching {
                AppleMusicMotionCoverManager.downloadMotionMp4(masterUrl, outFile)
            }.getOrDefault(false)
            if (!ok || !outFile.isFile || outFile.length() == 0L) {
                outFile.delete()
                return@withLock null
            }
            outFile.setLastModified(System.currentTimeMillis())
            AppleMusicMotionCoverManager.trimCache(dir, activePlayingPath)
            outFile.absolutePath
        }
    }

    private fun normalizeArtistKey(artistName: String): String? =
        artistNameCandidates(artistName)
            .firstNotNullOfOrNull { candidate ->
                AppleMusicMotionCoverManager.norm(candidate).ifEmpty { null }
            }

    /**
     * NetEase often returns names such as `皇昶枢（MULA SAKEE）`, while Apple Music indexes the
     * artist as `MULA SAKEE`. Try parenthesized aliases first, then the regular first-artist form.
     */
    internal fun artistNameCandidates(artistName: String): List<String> {
        val trimmed = artistName.trim()
        if (trimmed.isEmpty()) return emptyList()
        val aliases = Regex("[（(]([^（）()]+)[）)]")
            .findAll(trimmed)
            .map { it.groupValues[1].trim() }
            .filter { alias -> alias.isNotEmpty() && alias.any { it.code < 128 && it.isLetterOrDigit() } }
            .toList()
        val first = AppleMusicMotionCoverManager.firstArtist(trimmed).ifBlank { trimmed }
        return (aliases + first + trimmed)
            .distinctBy(AppleMusicMotionCoverManager::norm)
    }

    private fun motionCacheFile(context: Context, key: String): File =
        File(AppleMusicMotionCoverManager.cacheDir(context), "artist_$key.mp4")

    internal fun extractArtistLinkCandidates(jsonBody: String, targetArtist: String): List<String> {
        val wantExact = AppleMusicMotionCoverManager.norm(targetArtist)
        val wantFirst = AppleMusicMotionCoverManager.norm(AppleMusicMotionCoverManager.firstArtist(targetArtist))
        if (wantExact.isEmpty() && wantFirst.isEmpty()) return emptyList()

        val results = runCatching { JSONObject(jsonBody).optJSONArray("results") }.getOrNull() ?: return emptyList()
        data class RankedCandidate(val url: String, val score: Int, val order: Int)
        val ranked = mutableListOf<RankedCandidate>()
        val seenUrls = HashSet<String>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val link = item.optString("artistLinkUrl", "").trim()
            if (!link.startsWith("https://") || !seenUrls.add(link)) continue
            val name = item.optString("artistName", "")
            val normName = AppleMusicMotionCoverManager.norm(name)
            if (normName.isEmpty()) continue
            val score = when {
                wantExact.isNotEmpty() && normName == wantExact -> 3
                wantFirst.isNotEmpty() && normName == wantFirst -> 2
                wantFirst.isNotEmpty() && (normName.contains(wantFirst) || wantFirst.contains(normName)) -> 1
                else -> 0
            }
            if (score > 0) {
                ranked += RankedCandidate(link, score, i)
            }
        }
        return ranked
            .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.order })
            .take(MAX_CANDIDATES)
            .map { it.url }
    }

    internal fun extractArtistLogoUrl(sections: JSONArray, maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX): String? =
        extractArtistAssets(sections, maxLogoWidthPx = maxWidthPx)?.logoUrl

    internal fun extractArtistAssets(
        sections: JSONArray,
        maxLogoWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
        maxArtworkSizePx: Int = DEFAULT_MAX_ARTWORK_PX,
    ): AppleMusicArtistAssets? {
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val id = sec.optString("id", "")
            val items = sec.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val item = items.optJSONObject(k) ?: continue
                val looksLikeArtistHeader = id.contains("ArtistDetailHeader", ignoreCase = true) ||
                    item.has("artistLogo") ||
                    item.has("videoArtwork") ||
                    item.has("wideVideoArtwork") ||
                    item.has("circleArtwork")
                if (!looksLikeArtistHeader) continue

                val logoUrl = formatImageTemplateUrl(
                    holder = item.optJSONObject("artistLogo"),
                    maxWidthPx = maxLogoWidthPx,
                    maxHeightPx = maxLogoWidthPx,
                    defaultCropStyle = "bb",
                    format = "png",
                )
                val artworkUrl = listOf(
                    item.optJSONObject("artwork"),
                    item.optJSONObject("colorBackdropArtwork"),
                    item.optJSONObject("circleArtwork"),
                ).firstNotNullOfOrNull { holder ->
                    formatImageTemplateUrl(
                        holder = holder,
                        maxWidthPx = maxArtworkSizePx,
                        maxHeightPx = maxArtworkSizePx,
                        defaultCropStyle = "sr",
                        format = "jpg",
                    )
                }
                val motionMasterUrl = listOf(
                    item.optJSONObject("videoArtwork")
                        ?.optJSONObject("dictionary")
                        ?.optJSONObject("motionArtistSquare1x1")
                        ?.optString("video", ""),
                    item.optJSONObject("wideVideoArtwork")
                        ?.optJSONObject("dictionary")
                        ?.optJSONObject("motionArtistFullscreen16x9")
                        ?.optString("video", ""),
                ).firstOrNull { !it.isNullOrBlank() && it.startsWith("https://") }

                if (logoUrl != null || artworkUrl != null || motionMasterUrl != null) {
                    return AppleMusicArtistAssets(
                        logoUrl = logoUrl,
                        artworkUrl = artworkUrl,
                        motionMasterUrl = motionMasterUrl,
                    )
                }
            }
        }
        return null
    }

    private fun formatImageTemplateUrl(
        holder: JSONObject?,
        maxWidthPx: Int,
        maxHeightPx: Int,
        defaultCropStyle: String,
        format: String,
    ): String? {
        holder ?: return null
        val dict = holder.optJSONObject("dictionary") ?: return null
        val template = dict.optString("url", "").trim()
        if (!template.startsWith("https://")) return null

        val origW = dict.optInt("width", 0)
        val origH = dict.optInt("height", 0)
        val cropStyle = holder.optString("cropStyle", "").ifBlank { defaultCropStyle }
        val maxW = maxWidthPx.coerceAtLeast(1)
        val maxH = maxHeightPx.coerceAtLeast(1)
        val (targetW, targetH) = when {
            origW > 0 && origH > 0 -> {
                val scale = minOf(1f, minOf(maxW / origW.toFloat(), maxH / origH.toFloat()))
                (origW * scale).roundToInt().coerceAtLeast(1) to
                    (origH * scale).roundToInt().coerceAtLeast(1)
            }
            origW > 0 -> {
                val w = minOf(origW, maxW)
                w to (w / if (format == "png") 4 else 1).coerceAtLeast(1)
            }
            origH > 0 -> {
                val h = minOf(origH, maxH)
                h to h
            }
            else -> {
                val s = max(maxW, 1)
                s to (if (format == "png") (s / 4).coerceAtLeast(1) else s)
            }
        }
        return template
            .replace("{w}", targetW.toString())
            .replace("{h}", targetH.toString())
            .replace("{c}", cropStyle)
            .replace("{f}", format)
    }

    private fun acceptLanguage(storefront: String): String =
        if (storefront == "cn") "zh-CN,zh;q=0.9,en;q=0.8" else "en-US,en;q=0.9"

    private fun getString(url: String, acceptLang: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", acceptLang)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code >= 500 || resp.code == 429) throw IOException("http ${resp.code}")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!isDirectory) mkdirs() }

    private fun ensureIndexLoadedSync(context: Context) {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            val indexFile = File(cacheDir(context), INDEX_NAME)
            if (indexFile.isFile) {
                runCatching {
                    indexFile.readLines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        val k = j.optString("k")
                        val status = j.optString("s")
                        if (k.isBlank() || status.isBlank()) return@forEach
                        indexCache[k] = CachedEntry(
                            status = status,
                            assets = AppleMusicArtistAssets(
                                logoUrl = j.optString("l").ifBlank { null },
                                artworkUrl = j.optString("a").ifBlank { null },
                                motionMasterUrl = j.optString("v").ifBlank { null },
                            ),
                            timestampMs = j.optLong("t", 0L),
                        )
                    }
                }
            }
            indexLoaded = true
        }
    }

    private suspend fun ensureIndexLoaded(context: Context) {
        if (indexLoaded) return
        indexMutex.withLock { ensureIndexLoadedSync(context) }
    }

    private suspend fun putIndexEntry(
        context: Context,
        key: String,
        status: String,
        assets: AppleMusicArtistAssets,
    ) {
        indexMutex.withLock {
            ensureIndexLoadedSync(context)
            indexCache[key] = CachedEntry(status, assets, System.currentTimeMillis())
            if (indexCache.size > 1000) {
                val oldestKey = indexCache.entries.minByOrNull { it.value.timestampMs }?.key
                if (oldestKey != null) indexCache.remove(oldestKey)
            }
            val lines = indexCache.entries.map { (k, entry) ->
                JSONObject().apply {
                    put("k", k)
                    put("s", entry.status)
                    put("t", entry.timestampMs)
                    entry.assets.logoUrl?.let { put("l", it) }
                    entry.assets.artworkUrl?.let { put("a", it) }
                    entry.assets.motionMasterUrl?.let { put("v", it) }
                }.toString()
            }
            val dir = cacheDir(context)
            val tmp = File(dir, "$INDEX_NAME.tmp")
            val dst = File(dir, INDEX_NAME)
            runCatching {
                tmp.writeText(lines.joinToString("\n"))
                if (!tmp.renameTo(dst)) {
                    dst.writeText(lines.joinToString("\n"))
                    tmp.delete()
                }
            }
        }
    }
}
