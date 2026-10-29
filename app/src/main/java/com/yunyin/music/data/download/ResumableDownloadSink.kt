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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Resumable download sink.
 *
 * Bytes are streamed into an app-private `.part` file with a [RandomAccessFile] at [DownloadRecord.downloadedBytes],
 * so an interrupted download survives a process death. The server is asked for `Range: bytes=<offset>-`; a 206 with
 * the matching `Content-Range` continues the file, while a 200 (Range ignored) discards the partial file and starts
 * over. Only a completed file is published to `Music/云音` through MediaStore, where the local-music scanner sees it.
 *
 * Records are written every ~1 MiB rather than every chunk: often enough that a killed process loses little, rare
 * enough that the persistence does not dominate the download.
 */
class ResumableDownloadSink(
    private val context: Context,
    private val client: OkHttpClient,
    private val log: LxSourceLog? = null,
    private val records: DownloadRecordStore? = null,
    /** Raw LRC for a track, so the saved file can carry a USLT frame and a sibling .lrc. */
    private val lyricFor: (suspend (Track) -> String?)? = null,
) : DownloadSink {

    private val partDir: File = File(context.filesDir, "downloads").apply { mkdirs() }

    override suspend fun save(
        track: Track,
        url: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): SavedDownload = withContext(Dispatchers.IO) {
        val part = File(partDir, "${track.id}.part")
        var record = records?.get(track.id)
        // A URL change or a missing record makes the old partial file unusable; never append a fresh
        // resource onto stale bytes from a different signed URL.
        if (record == null || record.url != url) {
            part.delete()
            record = record?.copy(url = url, downloadedBytes = 0L)
        }
        var offset = DownloadResumeRules.resumeOffset(record, url).coerceAtMost(part.length())
        if (part.exists() && part.length() < offset) offset = part.length()
        if (offset == 0L) part.delete()
        if (offset > 0L) log("action=resume id=${track.id} bytes=$offset/${record?.totalBytes ?: -1}")
        var restarted = false
        while (true) {
            val requestBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            DownloadResumeRules.rangeHeader(offset)?.let { requestBuilder.header("Range", it) }
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (DownloadResumeRules.mustRestart(response.code, offset)) {
                        // The CDN ignored Range. The partial file is unusable; report the fallback and restart once.
                        if (restarted) throw IOException("服务器不支持断点续传")
                        restarted = true
                        log("action=restart id=${track.id} reason=server-ignored-range")
                        part.delete()
                        offset = 0L
                        record = record?.copy(downloadedBytes = 0L, url = url)
                        return@use
                    }
                    if (offset > 0L && !DownloadResumeRules.responseIsResume(response.code, offset, response.header("Content-Range"))) {
                        throw IOException("断点续传响应不匹配（HTTP ${response.code}）")
                    }
                    val total = DownloadResumeRules.contentRangeTotal(response.header("Content-Range"))
                        ?: response.header("Content-Length")?.toLongOrNull()?.let { it + offset }
                    val contentType = response.header("Content-Type")
                    val extension = DownloadNaming.extensionFor(url, contentType, response.header("Content-Disposition"))
                        ?: throw MediaStoreAudioWriter.UnknownFormatException()
                    val body = response.body ?: throw IOException("响应为空")
                    RandomAccessFile(part, "rw").use { file ->
                        file.seek(offset)
                        file.setLength(offset)
                        var written = offset
                        var lastSaved = offset
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                file.write(buffer, 0, read)
                                written += read
                                onProgress(written, total)
                                if (written - lastSaved >= 1L * 1024 * 1024) {
                                    lastSaved = written
                                    persist(track, url, total, written, response.header("ETag"),
                                        response.header("Last-Modified"), extension, DownloadPhase.DOWNLOADING)
                                }
                            }
                        }
                        if (written <= 0L) throw IOException("返回的不是音频（0 字节）")
                        persist(track, url, total, written, response.header("ETag"),
                            response.header("Last-Modified"), extension, DownloadPhase.DOWNLOADING)
                        val saved = publish(track, part, extension)
                        records?.save(
                            DownloadRecord(
                                track = track,
                                quality = record?.quality.orEmpty(),
                                url = url,
                                totalBytes = total ?: written,
                                downloadedBytes = written,
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                phase = DownloadPhase.DONE,
                                fileName = saved.displayName,
                                uri = saved.audioUri,
                            ),
                        )
                        part.delete()
                        log("action=done id=${track.id} bytes=$written/$total")
                        return@withContext saved
                    }
                }
            } catch (e: CancellationException) {
                persist(track, url, record?.totalBytes, part.length(), record?.etag, record?.lastModified, "part", DownloadPhase.PAUSED)
                throw e
            } catch (t: Throwable) {
                persist(track, url, record?.totalBytes, part.length(), record?.etag, record?.lastModified, "part", DownloadPhase.FAILED, t.message)
                log("action=fail id=${track.id} bytes=${part.length()}/${record?.totalBytes ?: -1} reason=${t.message?.take(120)}")
                throw t
            }
        }
        // Unreachable: every loop iteration either returns, throws, or restarts.
        throw IOException("下载未完成")
    }

    private fun persist(
        track: Track,
        url: String,
        total: Long?,
        downloaded: Long,
        etag: String?,
        lastModified: String?,
        extension: String,
        phase: DownloadPhase,
        message: String? = null,
    ) {
        records?.save(
            DownloadRecord(
                track = track,
                url = url,
                totalBytes = total,
                downloadedBytes = downloaded,
                etag = etag,
                lastModified = lastModified,
                phase = phase,
                message = message,
                fileName = DownloadNaming.fileName(track.artistLine, track.name, extension),
            ),
        )
    }

    private suspend fun publish(track: Track, part: File, extension: String): SavedDownload {
        val resolver = context.contentResolver
        val base = DownloadNaming.fileName(track.artistLine, track.name, extension).substringBeforeLast('.', "")
        // Fetched before the insert: the cover goes *into* the audio file's ID3 tag, because a sibling
        // image alone is invisible to every other player.
        val cover = runCatching { fetchCover(track) }.getOrNull()
        val lyric = runCatching { lyricFor?.invoke(track) }.getOrNull()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, DownloadNaming.fileName(track.artistLine, track.name, extension))
            put(MediaStore.MediaColumns.MIME_TYPE, DownloadNaming.mimeFor(extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadNaming.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.TITLE, track.name)
            put(MediaStore.Audio.Media.ARTIST, track.artistLine)
            put(MediaStore.Audio.Media.ALBUM, track.albumName)
            put(MediaStore.Audio.Media.DURATION, track.durationMs)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore 拒绝创建文件")
        try {
            val tag = if (extension == "mp3") Mp3Tags.build(track, cover, lyric) else ByteArray(0)
            val flac = if (extension == "flac") FlacTags.build(part, track, cover, lyric) else null
            part.inputStream().use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    when {
                        flac != null -> {
                            output.write(flac.first)
                            if (flac.second > 0) input.skip(flac.second)
                        }
                        tag.isNotEmpty() -> {
                            output.write(tag)
                            // Skip the source's own tag when we just wrote one, so the file carries one tag.
                            val skip = Mp3Tags.existingTagLength(part)
                            if (skip > 0) input.skip(skip)
                        }
                    }
                    input.copyTo(output, 64 * 1024)
                } ?: throw IOException("无法打开存储输出流")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val coverUri = runCatching { writeSidecar(base, "jpg", "image/jpeg", cover) }.getOrNull()
            runCatching { writeSidecar(base, "lrc", "text/plain", lyric?.toByteArray(Charsets.UTF_8)) }
            // A private copy as well: MediaStore does not index `.lrc`, so the app itself needs a file
            // it can find by track id when the network is gone.
            runCatching { writePrivateLyric(track.id, lyric) }
            return SavedDownload(
                uri.toString(),
                values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
                DownloadNaming.RELATIVE_PATH,
                coverUri,
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** App-private lyric copy, keyed by track id; this is what offline playback reads. */
    private fun writePrivateLyric(trackId: Long, lyric: String?) {
        if (trackId <= 0L || lyric.isNullOrBlank()) return
        val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
        File(dir, "$trackId.lrc").writeText(lyric)
    }

    /** The cover bytes as served, or null when the track has none or the fetch fails. */
    private fun fetchCover(track: Track): ByteArray? {
        val url = track.coverUrl?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return null
        val request = Request.Builder().url(url).header("User-Agent", "YunYin/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_COVER_BYTES) return null
            return body.bytes().takeIf { it.isNotEmpty() }
        }
    }

    /** Writes a sibling file (cover image, lyric text) next to the audio, best-effort. */
    private fun writeSidecar(base: String, extension: String, mime: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadNaming.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    private fun log(message: String) {
        runCatching { log?.record("下载", "stage=download $message") }
    }

    private companion object {
        const val MAX_COVER_BYTES = 6L * 1024 * 1024
        const val USER_AGENT = "YunYin/1.0 (download)"
    }
}
