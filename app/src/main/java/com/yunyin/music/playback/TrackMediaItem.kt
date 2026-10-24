// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxTrackMemory

/**
 * Bridges [Track] to Media3 items.
 *
 * Stream URLs are short-lived and require an API call, so items are built with a
 * synthetic `netease://track/<id>` URI. [PlaybackService] installs a resolving data
 * source that swaps in the real URL at load time; this keeps the queue stable and
 * lets ExoPlayer handle gapless transitions and retries itself.
 *
 * ## Provider tracks travel with their identity
 *
 * A track from another platform (e.g. QQ Music) has no NetEase id: its [Track.id] is a synthetic negative id and
 * the real identity is [Track.providerRef]. The identifier that survives inside Media3 is the media id, so the ref
 * is copied into the item's **extras** as well: a media session that is restored (service recreation, process
 * restart while the session lives) can then rebuild the provider identity from the item alone, and the resolver's
 * persisted `ProviderTrackStore` covers the rest.
 */
@UnstableApi
object TrackMediaItem {

    private const val SCHEME = "netease"
    private const val HOST = "track"

    private const val EXTRA_PROVIDER = "lx.provider"
    private const val EXTRA_PROVIDER_ID = "lx.providerId"
    private const val EXTRA_MEDIA_MID = "lx.mediaMid"
    private const val EXTRA_ALBUM_MID = "lx.albumMid"
    private const val EXTRA_INTERVAL = "lx.interval"
    private const val EXTRA_QUALITIES = "lx.qualities"
    private const val EXTRA_TYPES = "lx.typeSizes"
    private const val EXTRA_DURATION = "lx.durationMs"
    private const val EXTRA_SONG_ID = "lx.songId"

    fun uriFor(trackId: Long): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath("$trackId").build()

    fun trackIdOf(uri: Uri): Long? {
        if (uri.scheme != SCHEME) return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    fun mediaItem(track: Track): MediaItem {
        // The queue is the one place that holds the full Track; the stream resolver later sees only the synthetic
        // id URI on a loading thread. Remembering it here is what lets a custom source receive a complete
        // `musicInfo` (name/artist/album/duration) instead of an id alone.
        LxTrackMemory.remember(track)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artistLine)
            .setAlbumTitle(track.albumName)
            .setArtworkUri(track.coverUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extrasFor(track))
            .build()

        return MediaItem.Builder()
            .setMediaId("${track.id}")
            .setUri(uriFor(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun extrasFor(track: Track): Bundle = Bundle().apply {
        putLong(EXTRA_DURATION, track.durationMs)
        val ref = track.providerRef ?: return@apply
        putString(EXTRA_PROVIDER, ref.provider)
        putString(EXTRA_PROVIDER_ID, ref.providerId)
        putString(EXTRA_SONG_ID, ref.songId)
        putString(EXTRA_MEDIA_MID, ref.mediaMid)
        putString(EXTRA_ALBUM_MID, ref.albumMid)
        putInt(EXTRA_INTERVAL, ref.intervalSeconds)
        putStringArrayList(EXTRA_QUALITIES, ArrayList(ref.qualities))
        // `tier=size` pairs; LX's `types`/`_types` strings, needed to rebuild the exact source contract on restore.
        putStringArrayList(
            EXTRA_TYPES,
            ArrayList(ref.typeSizes.map { (tier, size) -> "$tier=$size" }),
        )
    }

    /**
     * Rebuilds a [Track] from a queue item so the UI can render what the player holds.
     *
     * The provider ref is restored from the extras when present, which is what keeps a provider track playable
     * after the queue item is rebuilt (e.g. by a media-session restore) instead of being mistaken for a NetEase id.
     */
    fun trackOf(item: MediaItem): Track {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val provider = extras?.getString(EXTRA_PROVIDER)
        val providerId = extras?.getString(EXTRA_PROVIDER_ID)
        val providerRef = if (!provider.isNullOrBlank() && !providerId.isNullOrBlank()) {
            ProviderRef(
                provider = provider,
                providerId = providerId,
                songId = extras.getString(EXTRA_SONG_ID),
                mediaMid = extras.getString(EXTRA_MEDIA_MID),
                albumMid = extras.getString(EXTRA_ALBUM_MID),
                intervalSeconds = extras.getInt(EXTRA_INTERVAL),
                qualities = extras.getStringArrayList(EXTRA_QUALITIES).orEmpty(),
                typeSizes = extras.getStringArrayList(EXTRA_TYPES).orEmpty()
                    .mapNotNull { pair ->
                        val tier = pair.substringBefore('=')
                        val size = pair.substringAfter('=', "")
                        if (tier.isBlank()) null else tier to size
                    }
                    .toMap(),
            )
        } else {
            null
        }
        return Track(
            id = item.mediaId.toLongOrNull() ?: 0L,
            name = metadata.title?.toString().orEmpty(),
            artists = metadata.artist?.toString()?.split(" / ")?.filter { it.isNotBlank() }.orEmpty(),
            albumName = metadata.albumTitle?.toString().orEmpty(),
            albumId = 0L,
            coverUrl = metadata.artworkUri?.toString(),
            durationMs = extras?.getLong(EXTRA_DURATION, 0L) ?: 0L,
            providerRef = providerRef,
        )
    }
}
