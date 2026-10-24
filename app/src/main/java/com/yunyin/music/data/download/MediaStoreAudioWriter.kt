// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Writes a downloaded track into the user-visible `Music/云音` collection through MediaStore.
 *
 * ## Why MediaStore and pending rows
 *
 * minSdk is 33, so there is no storage permission and no direct path into shared storage. A row is inserted with
 * [MediaStore.MediaColumns.IS_PENDING] = 1, bytes are streamed into it, and only then is it published — other apps
 * never see a half-written or 0-byte item. Any failure or cancellation **deletes the pending row**.
 *
 * ## Folder fallback
 *
 * The intended collection is `Music/云音`, but a Chinese sub-folder under Music is exactly the kind of `RELATIVE_PATH`
 * some vendor ROMs reject. The writer therefore retries `Music/云音` → `Music` → `Music/YunYin` (ASCII), re-requesting
 * the URL only if the *write* (not the insert) failed, and records which folder worked. The exception is never
 * swallowed: the manager's message names the last MediaStore error.
 *
 * ## Content checks
 *
 * The extension is taken from the response (Content-Type → URL path → Content-Disposition); an unrecognisable
 * response is refused. The first 16 KiB are sniffed for HTML/JSON error bodies served with HTTP 200, and the written
 * byte count must be non-zero before the row is published.
 *
 * The cover is saved as a sibling image in the same folder. Lyrics are **not** written in phase 1: MediaStore has no
 * general "any file in Music" collection, so a `.lrc` sibling needs SAF or a `Downloads/`-relative entry (phase 2).
 */
class MediaStoreAudioWriter(
    private val context: Context,
    private val client: OkHttpClient,
    private val log: LxSourceLog? = null,
) : DownloadSink {

    /** The source answered with a non-2xx status: the URL is expired or refused, not a network failure. */
    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** The response was audio but neither its type nor its URL said which container it is. */
    class UnknownFormatException : IOException("无法识别音频格式")

    /** Audio body that was empty, or not audio at all (an HTML/JSON error page served with 200). */
    class NotAudioException(message: String) : IOException(message)

    override suspend fun save(
        track: Track,
        url: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): SavedDownload = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (folder in DownloadStoragePlan.folders) {
            try {
                val saved = saveInto(track, url, folder, onProgress)
                log("result=ok folder=$folder file=${saved.displayName} uri=${saved.audioUri}")
                return@withContext saved
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                log("layer=fail folder=$folder error=${t.javaClass.simpleName}: ${t.message}")
                // Only a storage-side failure is worth retrying in another folder; a network/HTTP failure is the same
                // everywhere and would just download the error three times.
                if (t is HttpStatusException || t is NotAudioException || t is UnknownFormatException) throw t
            }
        }
        throw lastError ?: IOException("存储写入失败")
    }

    private suspend fun saveInto(
        track: Track,
        url: String,
        folder: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): SavedDownload {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            log("layer=http status=${response.code} url=${url.substringBefore('?').take(160)}")
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val contentType = response.header("Content-Type")
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            val extension = DownloadNaming.extensionFor(url, contentType, response.header("Content-Disposition"))
                ?: throw UnknownFormatException()
            val resolver = context.contentResolver
            val base = DownloadNaming.baseName(track.artistLine, track.name)
            val audioNames = existingNames(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, folder)
            val displayName = DownloadNaming.uniqueFileName(base, extension) { it in audioNames }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, DownloadNaming.mimeFor(extension))
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.TITLE, track.name)
                put(MediaStore.Audio.Media.ARTIST, track.artistLine)
                put(MediaStore.Audio.Media.ALBUM, track.albumName)
                put(MediaStore.Audio.Media.DURATION, track.durationMs)
            }
            val uri = try {
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            } catch (t: Throwable) {
                log("layer=insert-failed folder=$folder error=${t.javaClass.simpleName}: ${t.message}")
                throw t
            } ?: throw IOException("MediaStore 拒绝创建文件（insert 返回 null）")
            log("layer=insert folder=$folder uri=$uri file=$displayName")

            try {
                val body = response.body ?: throw IOException("响应为空")
                body.byteStream().use { input ->
                    resolver.openOutputStream(uri)?.use { output ->
                        // Read a prefix first: a 200 HTML/JSON error body must be refused before it is written, and
                        // this is the only chance to inspect the content before the row exists.
                        val prefix = readPrefix(input, 16 * 1024)
                        AudioResponseCheck.failure(prefix, contentType)?.let { throw NotAudioException(it) }
                        var written = 0L
                        if (prefix.isNotEmpty()) {
                            output.write(prefix)
                            written += prefix.size
                            onProgress(written, total)
                        }
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // Cancelling the job must stop a long flac promptly; ensureActive throws here and the
                            // catch below deletes the pending row.
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                        output.flush()
                        if (written <= 0L) throw NotAudioException("返回的不是音频（0 字节）")
                        log("layer=write folder=$folder bytes=$written expected=${total ?: -1}")
                    } ?: throw IOException("无法打开存储输出流")
                }
                val published = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                if (published <= 0) {
                    // A row left pending is written but invisible to file managers/other players — the "download did
                    // nothing" symptom. Retry once, then fail with a message instead of leaving a hidden row.
                    log("layer=publish warning=update-returned-0 uri=$uri retrying")
                    val retried = resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    if (retried <= 0) {
                        throw IOException("存储发布失败（IS_PENDING 未被清除，文件对其他应用不可见）")
                    }
                }
                log("layer=publish uri=$uri folder=$folder")
            } catch (t: Throwable) {
                log("layer=cleanup folder=$folder error=${t.javaClass.simpleName}: ${t.message}")
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            val cover = runCatching { saveCover(track, folder) }.getOrNull()
            return SavedDownload(uri.toString(), displayName, folder, cover?.toString())
        }
    }

    /** Up to [limit] bytes from [input], without losing them: the caller writes the returned array first. */
    private fun readPrefix(input: java.io.InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = input.read(buffer, filled, limit - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    /** Best-effort sibling cover; a missing cover must not fail the audio download. */
    private fun saveCover(track: Track, folder: String): Uri? {
        val coverUrl = track.coverUrl?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        val request = Request.Builder().url(coverUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val extension = when {
                contentType.contains("png") -> "png"
                contentType.contains("webp") -> "webp"
                contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
                else -> coverUrl.substringAfterLast('.', "").lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
                    ?: return null
            }
            val resolver = context.contentResolver
            val base = DownloadNaming.baseName(track.artistLine, track.name)
            val names = existingNames(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, folder)
            val displayName = DownloadNaming.uniqueFileName(base, extension) { it in names }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, when (extension) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                })
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                (response.body ?: return null).byteStream().use { input ->
                    resolver.openOutputStream(uri)?.use { output -> input.copyTo(output, 64 * 1024) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                log("layer=cover uri=$uri folder=$folder")
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
        }
    }

    private fun existingNames(collection: Uri, folder: String): Set<String> {
        val names = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("$folder/"),
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (column >= 0) names += cursor.getString(column)
                }
            }
        }
        return names
    }

    private fun log(detail: String) {
        runCatching { log?.record("下载", "stage=download $detail") }
    }

    private companion object {
        const val USER_AGENT = "YunYin/1.0 (download)"
    }
}
