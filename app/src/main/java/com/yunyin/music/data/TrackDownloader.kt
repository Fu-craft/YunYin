package com.yunyin.music.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yunyin.music.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a track's audio into the device's public music collection.
 *
 * Files land in `Music/云音/` rather than the app's private storage, so they are visible to the
 * user's file manager and other players — which is the point of "download".
 *
 * Two things about the source that are easy to get wrong and both broke the first version:
 *
 *  - **The URL needs the session cookie.** NetEase's CDN rejects an anonymous request for a signed
 *    audio URL — often with a 403 — so fetching it with a bare `URL.openConnection()` fails even
 *    though the URL itself resolved fine. The cookie is passed in per call rather than stored, so a
 *    sign-out cannot leave a stale credential behind.
 *  - **The URL is the *reason* it failed, not the file.** Reporting the HTTP status makes a CDN
 *    refusal distinguishable from "no network", which matters because the fix differs.
 *
 * Written through `MediaStore` so no storage permission is required on API 29+; `minSdk` is 33, so
 * that is the only path that runs.
 */
class TrackDownloader(private val context: Context) {

    /** Where downloads appear, both in the collection and on disk. */
    private val relativeDir = "${Environment.DIRECTORY_MUSIC}/云音"

    /**
     * Result of a download attempt.
     *
     * A plain `Result` was not enough: "the CDN said 403" and "the disk write failed" need different
     * wording, and the user's report ("the animation played but nothing was saved") is exactly the
     * case where a generic message is useless.
     */
    sealed interface Outcome {
        data class Saved(val uri: Uri, val fileName: String, val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Downloads [url] and files it as [track].
     *
     * @param cookie the session cookie, or blank for a guest session.
     */
    suspend fun save(
        url: String,
        track: Track,
        cookie: String,
        extension: String = "mp3",
    ): Outcome = withContext(Dispatchers.IO) {
        val fileName = buildFileName(track, extension)
        try {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as? HttpURLConnection)
                    ?: return@withContext Outcome.Failed("不支持的下载地址")
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (cookie.isNotBlank()) {
                    connection.setRequestProperty("Cookie", cookie)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext Outcome.Failed(
                        when (status) {
                            403, 401 -> "服务器拒绝了下载（$status），请先登录后再试"
                            404 -> "音频文件不存在（404）"
                            else -> "下载失败：HTTP $status"
                        },
                    )
                }

                val (uri, written) = connection.inputStream.use { input ->
                    insertIntoMediaStore(input, fileName)
                }
                if (written <= 0) {
                    return@withContext Outcome.Failed("下载内容为空")
                }
                Outcome.Saved(uri, fileName, written)
            } finally {
                connection?.disconnect()
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "网络错误")
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "保存失败")
        }
    }

    /**
     * Streams [input] into a new MediaStore audio row.
     *
     * Returns the row's Uri and the number of bytes written. The row is deleted again if the write
     * fails, so a half-written file never appears in the user's library as a playable-but-broken
     * entry.
     */
    private fun insertIntoMediaStore(input: java.io.InputStream, fileName: String): Pair<Uri, Long> {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("系统媒体库拒绝创建条目")

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

        // Ask the media scanner to index it so other apps see it now rather than after a reboot.
        //
        // Wrapped, and deliberately *after* the write has succeeded: `scanFile` wants filesystem
        // paths, so handing it a content:// URI is not meaningful, and on some devices it throws.
        // Letting that propagate (the first version) turned a *successful* save into a reported
        // failure — which is precisely the "animation played but nothing was saved" symptom, since
        // the file was in fact on disk the whole time.
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(uri.toString()), null, null) }
        return uri to written
    }

    /**
     * Builds a readable, legal file name.
     *
     * Keeps "artist - title" because that is what a music collection expects, and strips the
     * characters no filesystem accepts. Truncated so a very long title cannot exceed the limit
     * MediaStore enforces.
     */
    private fun buildFileName(track: Track, extension: String): String {
        val artist = track.artistLine.substringBefore("/").trim()
        val base = if (artist.isBlank()) track.name else "$artist - ${track.name}"
        val safe = buildString {
            for (ch in base) {
                append(
                    when {
                        ch in ILLEGAL -> '_'
                        ch.code < 0x20 -> '_'
                        else -> ch
                    },
                )
            }
        }.trim().trimEnd('.').take(MAX_NAME)
        return "$safe.$extension"
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
        const val MAX_NAME = 120
        val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').toSet()
    }
}
