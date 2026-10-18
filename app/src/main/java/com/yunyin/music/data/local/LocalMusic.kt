package com.yunyin.music.data.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.yunyin.music.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Local device audio, as first-class tracks.
 *
 * ## Identity
 *
 * A MediaStore row is identified by its `_ID`; the app's player queue is keyed by `Track.id`, so a local id is a
 * deterministic **negative** synthetic id derived from the MediaStore id. `MusicRepository.streamUrl` answers these
 * ids with the row's `content://` URI, which is what lets the existing player, queue, mini player, progress, sleep
 * timer and background playback work unchanged (Media3's data source resolves content URIs).
 *
 * ## Filters
 *
 * Very short files (<20 s) are skipped: they are usually notification sounds or ringtones, and they make a music list
 * look broken. Duration is the only signal used.
 */
object LocalMusicRules {

    const val MIN_DURATION_MS = 20_000L

    fun isEligible(durationMs: Long): Boolean = durationMs >= MIN_DURATION_MS

    /** Negative, deterministic; local ids must never collide with NetEase (positive) ids. */
    fun syntheticId(mediaId: Long): Long = -mediaId - 1_000_000_000L

    fun trackOf(
        mediaId: Long,
        title: String?,
        artist: String?,
        album: String?,
        albumId: Long,
        durationMs: Long,
        displayName: String?,
    ): Track? {
        if (!isEligible(durationMs)) return null
        val name = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return Track(
            id = syntheticId(mediaId),
            name = name,
            artists = listOf(artist?.trim()?.takeIf { it.isNotEmpty() } ?: "未知艺人"),
            albumName = album?.trim().orEmpty(),
            albumId = albumId,
            coverUrl = null,
            durationMs = durationMs,
        )
    }
}

/** Process-wide map of local track id → MediaStore row; populated by a scan. */
class LocalMusicStore {
    private val tracksById = ConcurrentHashMap<Long, Track>()
    private val urisById = ConcurrentHashMap<Long, String>()

    fun remember(track: Track, contentUri: String) {
        tracksById[track.id] = track
        urisById[track.id] = contentUri
    }

    fun replaceAll(entries: List<Pair<Track, String>>) {
        tracksById.clear()
        urisById.clear()
        entries.forEach { (track, uri) -> remember(track, uri) }
    }

    fun all(): List<Track> = tracksById.values.sortedBy { it.name }
    fun count(): Int = tracksById.size
    fun uriFor(trackId: Long): String? = urisById[trackId]
}

/** Reads MediaStore.Audio into [LocalMusicStore]. Requires READ_MEDIA_AUDIO (API 33+). */
class LocalMusicScanner(
    private val context: Context,
    private val store: LocalMusicStore,
) {
    suspend fun scan(): Result<List<Track>> = withContext(Dispatchers.IO) {
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.IS_MUSIC,
            )
            val entries = ArrayList<Pair<Track, String>>()
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC}!=0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val track = LocalMusicRules.trackOf(
                        mediaId = mediaId,
                        title = cursor.getString(titleCol),
                        artist = cursor.getString(artistCol),
                        album = cursor.getString(albumCol),
                        albumId = cursor.getLong(albumIdCol),
                        durationMs = cursor.getLong(durationCol),
                        displayName = cursor.getString(nameCol),
                    ) ?: continue
                    val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                    entries += track to uri
                }
            }
            store.replaceAll(entries)
            Result.success(store.all())
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
