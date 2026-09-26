package com.yunyin.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.yunyin.music.core.Track

/**
 * Bridges [Track] to Media3 items.
 *
 * Stream URLs are short-lived and require an API call, so items are built with a
 * synthetic `netease://track/<id>` URI. [PlaybackService] installs a resolving data
 * source that swaps in the real URL at load time; this keeps the queue stable and
 * lets ExoPlayer handle gapless transitions and retries itself.
 */
@UnstableApi
object TrackMediaItem {

    private const val SCHEME = "netease"
    private const val HOST = "track"

    fun uriFor(trackId: Long): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath("$trackId").build()

    fun trackIdOf(uri: Uri): Long? {
        if (uri.scheme != SCHEME) return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    fun mediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artistLine)
            .setAlbumTitle(track.albumName)
            .setArtworkUri(track.coverUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId("${track.id}")
            .setUri(uriFor(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    /** Rebuilds a [Track] from a queue item so the UI can render what the player holds. */
    fun trackOf(item: MediaItem): Track {
        val metadata = item.mediaMetadata
        return Track(
            id = item.mediaId.toLongOrNull() ?: 0L,
            name = metadata.title?.toString().orEmpty(),
            artists = metadata.artist?.toString()?.split(" / ")?.filter { it.isNotBlank() }.orEmpty(),
            albumName = metadata.albumTitle?.toString().orEmpty(),
            albumId = 0L,
            coverUrl = metadata.artworkUri?.toString(),
            durationMs = 0L,
        )
    }
}
