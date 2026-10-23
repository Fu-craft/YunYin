package com.yunyin.music.data.local

import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.Context
import android.provider.MediaStore
import com.yunyin.music.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

/**
 * One local file, with the fields the library needs for grouping, sorting, search and artwork.
 *
 * [id] is the player-facing synthetic negative id; [mediaId], [albumId], [trackNumber], [year] and
 * [addedAtSec] are the MediaStore facts the pure collection rules group and sort by. [artPath] is a cached
 * artwork file path resolved from embedded APIC data or a folder image; null means the default placeholder.
 */
data class LocalSong(
    val id: Long,
    val mediaId: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val addedAtSec: Long,
    val artPath: String?,
    val displayName: String,
) {
    /** The player understands the existing Track shape; local playback resolves [id] to [contentUri]. */
    fun toTrack(): Track = Track(
        id = id,
        name = title,
        artists = listOf(artist.ifBlank { "未知艺人" }),
        albumName = album,
        albumId = albumId,
        // The player's cover pipeline receives this Track; a local artwork file must travel with it or the
        // player/mini-player has nothing to load. File URIs are handled by ArtworkLoader.
        coverUrl = artPath?.let { runCatching { java.io.File(it).toURI().toString() }.getOrNull() },
        durationMs = durationMs,
    )
}

/** A group of local songs sharing one album, in track-number order (unknown track numbers last). */
data class LocalAlbum(
    val name: String,
    val artist: String,
    val artPath: String?,
    val year: Int,
    val songs: List<LocalSong>,
)

/** A group of local album groups sharing one artist, albums ordered by year then name. */
data class LocalArtist(
    val name: String,
    val artPath: String?,
    val albums: List<LocalAlbum>,
) {
    val songCount: Int get() = albums.sumOf { it.songs.size }
}

/** Local library browse mode. */
enum class LocalGrouping(val label: String) {
    Songs("歌曲"),
    Albums("专辑"),
    Artists("艺人"),
}

/** Local song order. [label] is the chip text. */
enum class LocalSort(val label: String) {
    Title("标题"),
    Artist("艺人"),
    Album("专辑"),
    Added("添加时间"),
    Duration("时长"),
}

/**
 * Pure local-library transformations: search, sort, album grouping, artist grouping.
 *
 * Everything here is deterministic and independent of Android, so the ordering rules are unit-tested rather than
 * verified by eye on a device. Blank artist/album values collapse into the same "未知" bucket, because MediaStore
 * returns null/empty for files with no tags and one bucket per missing tag is noise in the browse list.
 */
object LocalMusicCollectionRules {

    const val UNKNOWN_ARTIST = "未知艺人"
    const val UNKNOWN_ALBUM = "未知专辑"

    fun search(songs: List<LocalSong>, query: String): List<LocalSong> {
        val needle = query.trim()
        if (needle.isEmpty()) return songs
        return songs.filter { song ->
            song.title.contains(needle, ignoreCase = true) ||
                song.artist.contains(needle, ignoreCase = true) ||
                song.album.contains(needle, ignoreCase = true)
        }
    }

    fun sortSongs(songs: List<LocalSong>, sort: LocalSort): List<LocalSong> {
        val byTitle = compareBy<LocalSong> { it.title.lowercase() }
        return when (sort) {
            LocalSort.Title -> songs.sortedWith(byTitle)
            LocalSort.Artist -> songs.sortedWith(
                compareBy<LocalSong> { it.artist.ifBlank { UNKNOWN_ARTIST }.lowercase() }.then(byTitle),
            )
            LocalSort.Album -> songs.sortedWith(
                compareBy<LocalSong> { it.album.ifBlank { UNKNOWN_ALBUM }.lowercase() }.then(byTitle),
            )
            LocalSort.Added -> songs.sortedWith(compareByDescending<LocalSong> { it.addedAtSec }.then(byTitle))
            LocalSort.Duration -> songs.sortedWith(compareBy<LocalSong> { it.durationMs }.then(byTitle))
        }
    }

    /** Albums sorted by name; each album's songs by track number (unknown last), then title. */
    fun albums(songs: List<LocalSong>): List<LocalAlbum> =
        songs.groupBy { albumKey(it) }
            .map { (_, group) -> albumOf(group) }
            .sortedWith(compareBy<LocalAlbum> { it.name.lowercase() })

    /** Artists sorted by name; each artist's albums by year (unknown last), then album name. */
    fun artists(songs: List<LocalSong>): List<LocalArtist> =
        songs.groupBy { it.artist.ifBlank { UNKNOWN_ARTIST } }
            .map { (name, group) ->
                val albums = albums(group).sortedWith(
                    compareBy<LocalAlbum> { if (it.year > 0) it.year else Int.MAX_VALUE }
                        .thenBy { it.name.lowercase() },
                )
                LocalArtist(
                    name = name,
                    artPath = albums.firstNotNullOfOrNull { it.artPath },
                    albums = albums,
                )
            }
            .sortedWith(compareBy<LocalArtist> { it.name.lowercase() })

    fun albumSongs(songs: List<LocalSong>): List<LocalSong> = sortByTrack(songs)

    private fun albumKey(song: LocalSong): String =
        if (song.albumId > 0L) "id:${song.albumId}"
        else "name:${song.album.trim().lowercase()}|${song.artist.trim().lowercase()}"

    private fun albumOf(group: List<LocalSong>): LocalAlbum {
        val first = group.first()
        return LocalAlbum(
            name = first.album.ifBlank { UNKNOWN_ALBUM },
            artist = first.artist.ifBlank { UNKNOWN_ARTIST },
            artPath = group.firstNotNullOfOrNull { it.artPath },
            year = group.firstOrNull { it.year > 0 }?.year ?: 0,
            songs = sortByTrack(group),
        )
    }

    private fun sortByTrack(songs: List<LocalSong>): List<LocalSong> = songs.sortedWith(
        compareBy<LocalSong> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy { it.title.lowercase() },
    )
}

/**
 * Artwork fallback order for a local album: conventional folder images first, then an image named after the
 * audio file. The resolver checks existence; keeping the order here makes it testable without a filesystem.
 */
object LocalArtworkRules {
    fun candidateNames(baseName: String): List<String> {
        val stem = baseName.substringBeforeLast('.').trim()
        val names = mutableListOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "albumart.jpg", "albumart.jpeg", "albumart.png",
        )
        if (stem.isNotEmpty()) {
            for (ext in listOf("jpg", "jpeg", "png", "webp")) names += "$stem.$ext"
        }
        return names.distinct()
    }
}

/**
 * Resolves local album artwork, once per album.
 *
 * Order: embedded APIC picture from [MediaMetadataRetriever] -> conventional folder image next to the audio file
 * (cover/folder/albumart or same-name) -> null, which the UI renders as the app's default placeholder. Embedded
 * bytes are cached under `cacheDir/local-art/` so scrolling does not re-decode a large JPEG per frame.
 */
class LocalArtworkResolver(private val context: Context) {
    // Empty string is the "resolved, no artwork" sentinel: ConcurrentHashMap cannot hold null values.
    private val resolved = ConcurrentHashMap<Long, String>()
    private val directory = File(context.cacheDir, "local-art")

    fun artworkFor(albumId: Long, contentUri: String, filePath: String?, displayName: String): String? {
        if (albumId > 0L) {
            resolved[albumId]?.let { return it.takeIf { cached -> cached.isNotEmpty() } }
        }
        val found = runCatching { embedded(contentUri, albumId) }.getOrNull()
            ?: runCatching { folderImage(filePath, displayName) }.getOrNull()
        if (albumId > 0L) resolved[albumId] = found.orEmpty()
        return found
    }

    private fun embedded(contentUri: String, albumId: Long): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(contentUri))
            val bytes = retriever.embeddedPicture ?: return null
            directory.mkdirs()
            val file = File(directory, "album-${albumId}-${bytes.size}.img")
            if (!file.isFile || file.length().toInt() != bytes.size) file.writeBytes(bytes)
            file.absolutePath
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun folderImage(filePath: String?, displayName: String): String? {
        val parent = filePath?.let { File(it).parentFile } ?: return null
        if (!parent.isDirectory) return null
        return LocalArtworkRules.candidateNames(displayName)
            .asSequence()
            .map { File(parent, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }
}

/** Result of one MediaStore scan: usable songs plus how many rows were skipped as unreadable. */
data class LocalMusicScanResult(val songs: List<LocalSong>, val skipped: Int = 0)

/** Process-wide local-music index, populated by [LocalMusicScanner]. */
class LocalMusicStore {
    private val songsById = ConcurrentHashMap<Long, LocalSong>()

    fun replaceAll(songs: List<LocalSong>) {
        songsById.clear()
        songs.forEach { songsById[it.id] = it }
    }

    fun songs(): List<LocalSong> = songsById.values.sortedBy { it.title }
    fun count(): Int = songsById.size
    fun songFor(trackId: Long): LocalSong? = songsById[trackId]
    fun uriFor(trackId: Long): String? = songsById[trackId]?.contentUri
}

/** Reads MediaStore.Audio into [LocalMusicStore]. Requires READ_MEDIA_AUDIO (API 33+). */
class LocalMusicScanner(
    private val context: Context,
    private val store: LocalMusicStore,
    private val artwork: LocalArtworkResolver = LocalArtworkResolver(context),
) {
    suspend fun scan(): Result<LocalMusicScanResult> = withContext(Dispatchers.IO) {
        runCatching {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.IS_MUSIC,
            )
            val songs = ArrayList<LocalSong>()
            var skipped = 0
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
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    // One corrupt row must never abort the whole library.
                    val row = runCatching {
                        val mediaId = cursor.getLong(idCol)
                        val durationMs = cursor.getLong(durationCol)
                        val base = LocalMusicRules.trackOf(
                            mediaId = mediaId,
                            title = if (titleCol >= 0) cursor.getString(titleCol) else null,
                            artist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                            album = if (albumCol >= 0) cursor.getString(albumCol) else null,
                            albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L,
                            durationMs = durationMs,
                            displayName = if (nameCol >= 0) cursor.getString(nameCol) else null,
                        ) ?: return@runCatching null
                        val contentUri = ContentUris.withAppendedId(collection, mediaId).toString()
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                        val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                        LocalSong(
                            id = base.id,
                            mediaId = mediaId,
                            contentUri = contentUri,
                            title = base.name,
                            artist = base.artists.firstOrNull().orEmpty(),
                            album = base.albumName,
                            albumId = base.albumId,
                            durationMs = durationMs,
                            trackNumber = if (trackCol >= 0) cursor.getInt(trackCol) else 0,
                            year = if (yearCol >= 0) cursor.getInt(yearCol) else 0,
                            addedAtSec = if (addedCol >= 0) cursor.getLong(addedCol) else 0L,
                            artPath = artwork.artworkFor(base.albumId, contentUri, filePath, displayName),
                            displayName = displayName,
                        )
                    }.getOrNull()
                    if (row == null) skipped++ else songs += row
                }
            }
            store.replaceAll(songs)
            LocalMusicScanResult(store.songs(), skipped)
        }
    }
}
