package com.yunyin.music.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yunyin.music.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a track's **cover art** into the device's picture collection.
 *
 * This is a cover downloader, not a track downloader. Long-pressing the artwork asks for the image
 * that is on screen — the album art — and an earlier version saved the audio stream instead, which is
 * a different thing entirely (and not what a long press on a *picture* should produce).
 *
 * Files go to `Pictures/云音/` via MediaStore, so they appear in the gallery and need no storage
 * permission on API 29+ (`minSdk` is 33, so that is the only path that runs).
 *
 * The cover is fetched at [COVER_SIZE], larger than anything the UI displays: the point of saving it
 * is usually to use it elsewhere, and NetEase's image CDN re-encodes to the requested size, so asking
 * for more costs only a slightly bigger transfer.
 */
class CoverDownloader(private val context: Context) {

    /** Result of a save attempt. */
    sealed interface Outcome {
        data class Saved(val fileName: String, val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Downloads [coverUrl] and files it as [track]'s cover.
     *
     * @param cookie the session cookie, or blank for a guest session.
     */
    suspend fun save(
        coverUrl: String?,
        track: Track,
        cookie: String,
        size: Int = COVER_SIZE,
    ): Outcome = withContext(Dispatchers.IO) {
        if (coverUrl.isNullOrBlank()) {
            return@withContext Outcome.Failed("这首歌没有封面")
        }
        val url = if (coverUrl.contains("?")) coverUrl else "$coverUrl?param=${size}y$size"
        val fileName = "${buildBaseName(track)}.jpg"

        try {
            val connection = (URL(url).openConnection() as? HttpURLConnection)
                ?: return@withContext Outcome.Failed("不支持的图片地址")
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (cookie.isNotBlank()) {
                connection.setRequestProperty("Cookie", cookie)
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext Outcome.Failed("下载封面失败：HTTP $status")
                }
                val written = connection.inputStream.use { insertIntoGallery(it, fileName) }
                if (written <= 0) {
                    return@withContext Outcome.Failed("封面内容为空")
                }
                Outcome.Saved(fileName, written)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "网络错误")
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "保存失败")
        }
    }

    /**
     * Streams [input] into a new MediaStore image row.
     *
     * The row is deleted again if the write fails, so a half-written image never appears in the
     * gallery. Note the ordering: the image is fully written *before* anything else can fail, so a
     * later failure can never be reported as a failed save when the file is in fact on disk.
     */
    private fun insertIntoGallery(input: java.io.InputStream, fileName: String): Long {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/云音")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("系统相册拒绝创建条目")

        var written = 0L
        try {
            resolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
                output.flush()
            } ?: throw IOException("无法写入文件")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        // Clear IS_PENDING only after the bytes are all there; until then the entry is hidden from
        // the gallery, which is what prevents it ever being seen half-written.
        runCatching {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }
        return written
    }

    /**
     * "artist - title", made legal for a filename.
     *
     * Strips the characters no filesystem accepts and caps the length, since MediaStore rejects very
     * long names outright.
     */
    private fun buildBaseName(track: Track): String {
        val artist = track.artistLine.substringBefore("/").trim()
        val base = if (artist.isBlank()) track.name else "$artist - ${track.name}"
        val safe = buildString {
            for (ch in base) {
                append(if (ch in ILLEGAL || ch.code < 0x20) '_' else ch)
            }
        }.trim().trimEnd('.').take(MAX_NAME)
        return safe.ifBlank { "cover" }
    }

    private companion object {
        /** Requested cover edge, in px. Larger than any on-screen use. */
        const val COVER_SIZE = 1600

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
        const val MAX_NAME = 120
        val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').toSet()
    }
}
