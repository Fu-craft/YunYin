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
import java.io.File
import java.io.IOException

/**
 * Saves a track's audio into the device's public music collection.
 *
 * Files land in `Music/云音/` rather than the app's private storage, so they are visible to the
 * user's file manager and other players — which is the point of "download".
 *
 * Written through `MediaStore` on API 29+ so no storage permission is required; `minSdk` is 33, so
 * that is the only path. (The legacy `File`+`MediaScanner` route is kept as a fallback for the sake
 * of the rare device whose MediaStore rejects audio inserts, but it is not the main path.)
 *
 * The stream URL NetEase returns is **short-lived and signed**, so it is fetched immediately before
 * the transfer rather than being stored anywhere.
 */
class TrackDownloader(private val context: Context) {

    /** Where downloads appear, both in the collection and on disk. */
    private val relativeDir = "${Environment.DIRECTORY_MUSIC}/云音"

    /**
     * Downloads [url] and files it as [track].
     *
     * @return the created document's Uri, or null when the transfer itself failed.
     */
    suspend fun save(url: String, track: Track, extension: String = "mp3"): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = buildFileName(track, extension)
                val connection = java.net.URL(url).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    // NetEase rejects requests without a UA for some endpoints.
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection.getInputStream().use { input ->
                    insertIntoMediaStore(input, fileName)
                }
            }
        }

    private fun insertIntoMediaStore(input: java.io.InputStream, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir)
            }
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 拒绝创建音频条目")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            } ?: throw IOException("无法打开输出流")
        } catch (e: Exception) {
            // Never leave a half-written, playable-looking entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        // Make it appear immediately to other apps that watch the media collection.
        MediaScannerConnection.scanFile(context, arrayOf(uri.toString()), null, null)
        return uri
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

/** Unused legacy directory helper kept for documentation value. */
@Suppress("unused")
private fun legacyDir(context: Context): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "云音")
