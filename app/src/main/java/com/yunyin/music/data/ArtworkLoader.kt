package com.yunyin.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Image pipeline for album art.
 *
 * Three levels, in order: an in-memory [LruCache], a bounded **disk** cache, then the network.
 *
 * The disk level matters for scrolling. The screens show far more covers than fit in memory, so a
 * memory-only cache evicts the covers at the top of a list while the user scrolls down; scrolling
 * back up then re-downloaded every one of them mid-scroll. Persisting the bytes means a re-visit is
 * a local decode instead of a network round trip.
 *
 * Requests are de-duplicated, so a list of 100 rows sharing covers decodes each URL once.
 */
class ArtworkLoader(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Budget a quarter of the app heap, expressed in KB for LruCache.
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val diskDir = File(context.cacheDir, "artwork").apply { mkdirs() }

    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Resolves an artwork URL to the requested square size.
     *
     * NetEase serves several sizes with `?param=WxH`, but some endpoints hand back URLs that already
     * carry an image-processing chain (`?imageView=1&thumbnail=800y800&…&thumbnail=140y140`). The
     * old rule ("if it already has a query, leave it alone") therefore asked the CDN for that chain —
     * 140×140 on category pages — and the card upscaled it into a blurry tile. The rule is delegated
     * to [ArtworkUrlRules] so it is pure and unit-tested.
     */
    fun sized(url: String?, size: Int): String? = ArtworkUrlRules.sized(url, size)

    suspend fun load(url: String?, size: Int = 600): Bitmap? {
        val target = sized(url, size) ?: return null
        memory.get(target)?.let { return it }

        val deferred = synchronized(inFlight) {
            inFlight[target] ?: ioScope.async {
                // Disk first, network only as a last resort.
                readFromDisk(target) ?: fetchAndPersist(target)
            }.also { inFlight[target] = it }
        }
        return try {
            deferred.await()?.also { bitmap -> memory.put(target, bitmap) }
        } finally {
            synchronized(inFlight) { inFlight.remove(target) }
        }
    }

    /**
     * Decodes a local image file (the user's chosen avatar or header background).
     *
     * Kept in this class so it shares the memory cache and the off-main-thread decode; the network tiers
     * are simply skipped, since the bytes are already local. The cache key includes the file's length and
     * last-modified time, so replacing the file with new content at the same path cannot serve a stale
     * bitmap from memory.
     */
    suspend fun loadLocal(file: File?, size: Int = 600): Bitmap? {
        if (file == null || !file.isFile) return null
        val key = "file:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        memory.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { decodeDownsampled(file, size) }.getOrNull()
        } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /**
     * Decodes with a power-of-two `inSampleSize`, so a 4000px photo does not allocate a full-size bitmap
     * just to be drawn in a 96dp circle.
     */
    private fun decodeDownsampled(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    // ---------------------------------------------------------------- disk tier

    private fun cacheFile(key: String): File = File(diskDir, hash(key))

    private fun readFromDisk(key: String): Bitmap? {
        val file = cacheFile(key)
        if (!file.exists()) return null
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
        if (bitmap == null) {
            // A truncated or unreadable entry must not poison the cache forever.
            runCatching { file.delete() }
        } else {
            // Touch so the LRU sweep treats it as recently used.
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        return bitmap
    }

    private suspend fun fetchAndPersist(key: String): Bitmap? {
        val bytes = fetchBytes(key) ?: return null
        // Decode before writing, so a corrupt response is never persisted.
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        runCatching {
            cacheFile(key).writeBytes(bytes)
            pruneDisk()
        }
        return bitmap
    }

    private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    /** Drops least-recently-used files until the directory is back under [DISK_BUDGET_BYTES]. */
    private fun pruneDisk() {
        val files = diskDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET_BYTES) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= DISK_BUDGET_BYTES) return@forEach
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
    }

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) YunYin/1.0"

        /** Disk budget for cached artwork. */
        const val DISK_BUDGET_BYTES = 120L * 1024 * 1024
    }
}

/**
 * Pure URL rule for artwork sizing.
 *
 * NetEase's CDN serves `?param=WxH` on the bare `.jpg`; the API sometimes adds its own processing
 * chain, whose final `thumbnail=…` wins. For NetEase hosts the query is therefore replaced with the
 * requested `param`, so every surface gets the same resolution no matter which endpoint produced the
 * URL. Non-NetEase URLs (signed/processing URLs owned by other hosts) are left exactly as given.
 */
internal object ArtworkUrlRules {

    fun sized(url: String?, size: Int): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty() || size <= 0) return null
        if (raw.startsWith("data:") || raw.startsWith("file:")) return raw
        val base = raw.substringBefore('?')
        if (isNeteaseHost(base)) return "$base?param=${size}y$size"
        return if (raw.contains('?')) raw else "$raw?param=${size}y$size"
    }

    private fun isNeteaseHost(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(' ').lowercase()
        return host == "126.net" || host.endsWith(".126.net") ||
            host == "163.com" || host.endsWith(".163.com") ||
            host == "netease.com" || host.endsWith(".netease.com")
    }
}
