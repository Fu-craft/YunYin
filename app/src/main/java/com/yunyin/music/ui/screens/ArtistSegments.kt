package com.yunyin.music.ui.screens

/**
 * Maps a joined artist line ("A / B / C") to independently tappable ranges.
 *
 * ## The bug this replaces
 *
 * The previous implementation built separators as `"$artist / "` (the **name plus** the separator) and then
 * subtracted the separator length from the next cut. For every artist except the last that produced an empty
 * range (`0 until 0`), so only the final name had a hit target — "多人合作只能点到最后一个人". Here the names are
 * located in order and the separators are simply the gaps between them, so they are inert by construction.
 *
 * Pure and order-preserving: duplicate names still get one span each (the search cursor advances).
 */
internal object ArtistSegments {

    data class Segment(val index: Int, val range: IntRange)

    fun segments(line: String, artists: List<String>): List<Segment> {
        if (artists.isEmpty()) return emptyList()
        if (artists.size == 1) return listOf(Segment(0, 0 until line.length))
        val result = ArrayList<Segment>(artists.size)
        var cursor = 0
        artists.forEachIndexed { index, artist ->
            val at = if (artist.isEmpty()) -1 else line.indexOf(artist, cursor)
            if (at < 0) return listOf(Segment(0, 0 until line.length))
            result += Segment(index, at until (at + artist.length))
            cursor = at + artist.length
        }
        return result
    }
}
