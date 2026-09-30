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
 * avoids pulling in a serialization dependency for one field. The format itself lives in [TrackText],
 * shared with the liked-songs store so a change to one cannot silently break the other.
 */
class PlayHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amll_history", Context.MODE_PRIVATE)

    fun load(): List<Track> =
        prefs.getString(KEY, null)
            ?.lineSequence()
            ?.mapNotNull(TrackText::decode)
            ?.toList()
            .orEmpty()

    /** Records a play, moving an existing entry to the front rather than duplicating it. */
    fun record(track: Track) {
        if (track.id == 0L) return
        val updated = (listOf(track) + load().filterNot { it.id == track.id }).take(MAX)
        prefs.edit().putString(KEY, updated.joinToString("\n") { TrackText.encode(it) }).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "recent"
        const val MAX = 60
    }
}
