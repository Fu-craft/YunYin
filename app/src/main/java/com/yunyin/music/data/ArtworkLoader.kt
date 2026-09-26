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

    /** NetEase serves several sizes; `?param=WxH` returns a resized JPEG. */
    fun sized(url: String?, size: Int): String? =
        url?.let { if (it.contains("?")) it else "$it?param=${size}y$size" }

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
