package com.yunyin.music.data

import android.content.Context
import android.content.SharedPreferences
import com.yunyin.music.core.Track

/**
 * Songs the user liked *in this app*.
 *
 * ## Why a local list exists at all
 *
 * Liking used to be a write straight to NetEase (`/like`). That made the heart depend on the network and
 * on a signed-in account, so tapping it with no account — or on a bad connection — did nothing visible,
 * which reads as a broken control. It also meant the in-app list could only ever be the cloud list.
 *
 * The product decision now is the other way round: **liking is local**, it takes effect immediately and
 * works for guests, and the library's liked list shows the local likes *merged with* whatever NetEase
 * reports. Cloud writes are deliberately not attempted, so nothing here can fail for account reasons.
 *
 * The list therefore also has to survive a track that no longer resolves: the track is stored whole rather
 * than as an id, so a liked row can be rendered (title, artist, cover) without a network round trip.
 *
 * Storage follows [PlayHistoryStore]: delimited text in `SharedPreferences`. The payload is a short flat
 * list of scalars, so this avoids a serialization dependency for one field, and the encoding is shared
 * with the history store so the two cannot disagree about how a [Track] is written.
 */
class LikedSongsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amll_liked", Context.MODE_PRIVATE)

    /** Liked tracks, most recently liked first. */
    fun load(): List<Track> =
        prefs.getString(KEY, null)
            ?.lineSequence()
            ?.mapNotNull(TrackText::decode)
            ?.toList()
            .orEmpty()

    /** Whether [id] is in the local list. */
    fun contains(id: Long): Boolean = load().any { it.id == id }

    /**
     * Adds [track] to the front of the list, or removes it when [like] is false.
     *
     * Re-liking an existing track moves it to the front rather than duplicating it, which keeps the list a
     * set with a most-recent-first order.
     */
    fun setLiked(track: Track, like: Boolean) {
        if (track.id == 0L) return
        val current = load()
        val updated = if (like) {
            listOf(track) + current.filterNot { it.id == track.id }
        } else {
            current.filterNot { it.id == track.id }
        }
        write(updated)
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun write(tracks: List<Track>) {
        // Bounded like the history list: this is a convenience list, not the authoritative copy, so an
        // unbounded growth of a delimited preference string is not worth the risk.
        val text = tracks.take(MAX).joinToString("\n") { TrackText.encode(it) }
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val KEY = "liked"
        const val MAX = 2000
    }
}

/**
 * The wire format for a [Track] in `SharedPreferences`.
 *
 * Shared by the liked list and the play history so the two stores cannot drift: a field added to one
 * encoding has to be added here, in one place. Decoding is tolerant — a row with too few fields is
 * skipped rather than throwing, so a truncated write can only ever lose that row.
 */
internal object TrackText {

    private const val FIELD_SEP = "\u0001"
    private const val ARTIST_SEP = "\u0002"
    private const val FIELDS = 7

    fun encode(t: Track): String = listOf(
        t.id.toString(),
        t.name,
        t.artists.joinToString(ARTIST_SEP),
        t.albumName,
        t.coverUrl.orEmpty(),
        t.durationMs.toString(),
        t.fee.toString(),
    ).joinToString(FIELD_SEP)

    fun decode(line: String): Track? {
        val parts = line.split(FIELD_SEP)
        if (parts.size < FIELDS) return null
        val id = parts[0].toLongOrNull() ?: return null
        if (id == 0L) return null
        return Track(
            id = id,
            name = parts[1],
            artists = parts[2].split(ARTIST_SEP).filter { it.isNotBlank() },
            albumName = parts[3],
            albumId = 0L,
            coverUrl = parts[4].ifBlank { null },
            durationMs = parts[5].toLongOrNull() ?: 0L,
            fee = parts[6].toIntOrNull() ?: 0,
        )
    }
}
