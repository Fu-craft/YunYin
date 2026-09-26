package com.yunyin.music.data

import android.content.Context
import android.content.SharedPreferences
import com.yunyin.music.core.Track

/**
 * Locally recorded play history, newest first.
 *
 * The NetEase listening-record endpoint needs a signed-in account, so history is kept
 * on-device. That makes the library's "最近播放" section truthful for guests too, instead
 * of being filled with recommendation data (which is what it previously did).
 *
 * Rows are stored as delimited text: the payload is a short, flat list of scalars, so this
 * avoids pulling in a serialization dependency for one field.
 */
class PlayHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amll_history", Context.MODE_PRIVATE)

    fun load(): List<Track> =
        prefs.getString(KEY, null)
            ?.lineSequence()
            ?.mapNotNull(::decode)
            ?.toList()
            .orEmpty()

    /** Records a play, moving an existing entry to the front rather than duplicating it. */
    fun record(track: Track) {
        if (track.id == 0L) return
        val updated = (listOf(track) + load().filterNot { it.id == track.id }).take(MAX)
        prefs.edit().putString(KEY, updated.joinToString("\n") { encode(it) }).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    // Field separator / list separator: control characters that cannot appear in the data.
    private fun encode(t: Track) = listOf(
        t.id.toString(),
        t.name,
        t.artists.joinToString(ARTIST_SEP),
        t.albumName,
        t.coverUrl.orEmpty(),
        t.durationMs.toString(),
        t.fee.toString(),
    ).joinToString(FIELD_SEP)

    private fun decode(line: String): Track? {
        val parts = line.split(FIELD_SEP)
        if (parts.size < 7) return null
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

    private companion object {
        const val KEY = "recent"
        const val MAX = 60
        const val FIELD_SEP = "\u0001"
        const val ARTIST_SEP = "\u0002"
    }
}
