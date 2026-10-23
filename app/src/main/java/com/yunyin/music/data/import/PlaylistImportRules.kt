package com.yunyin.music.data.import

import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.ProviderMatcher

/** One non-empty line of pasted text, split into the fields the matcher needs. */
data class ImportedLine(
    val title: String,
    val artist: String,
    /** The user's original line, kept for the unmatched list. */
    val raw: String,
)

/**
 * Pure text playlist-import rules: text parsing, NetEase playlist link/ID parsing and match decisions.
 *
 * Matching reuses [ProviderMatcher], the same name + singer relation (and duration tolerance when a duration is
 * known) used for cross-provider playback, so an imported "title - artist" cannot silently become a cover by another
 * singer. The text import has no duration, so the interval guard is naturally inert.
 */
object PlaylistImportRules {

    private val SEPARATORS = listOf(" - ", " – ", " — ", " | ", "\t", "：", ":")
    private val HYPHEN = Regex("""\s+[-\u2013\u2014]\s+""")
    private val PLAYLIST_ID = Regex("""(?:[?&]id=|/playlist/)(\d{3,})""")

    /** Parses `title - artist` lines; blank lines and `#` comments are ignored. */
    fun parseText(text: String): List<ImportedLine> = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val split = splitLine(line) ?: return@mapNotNull null
            ImportedLine(title = split.first.trim(), artist = split.second.trim(), raw = line)
        }
        .filter { it.title.isNotEmpty() }
        .toList()

    private fun splitLine(line: String): Pair<String, String>? {
        for (separator in SEPARATORS) {
            val at = line.indexOf(separator)
            if (at > 0) return line.substring(0, at) to line.substring(at + separator.length)
        }
        HYPHEN.find(line)?.let { match ->
            if (match.range.first > 0) {
                return line.substring(0, match.range.first) to line.substring(match.range.last + 1)
            }
        }
        // No separator: the whole line is a title (an empty singer imposes no singer constraint in ProviderMatcher).
        return line to ""
    }

    /** Accepts a bare numeric playlist id or a NetEase `/playlist?id=…` URL. */
    fun parsePlaylistId(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toLongOrNull()?.takeIf { it > 0L }
        val match = PLAYLIST_ID.find(text) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
    }

    /** Splits ids into request-sized batches, dropping blanks while preserving order. */
    fun chunks(ids: List<Long>, size: Int = 100): List<List<Long>> {
        if (ids.isEmpty() || size <= 0) return emptyList()
        return ids.chunked(size)
    }

    /**
     * Best candidate for a pasted line, or null when nothing is related enough.
     *
     * Uses the same name + singer guard as cross-provider playback; with no duration in the pasted text the interval
     * check is inert, which is why an empty singer still requires a related title.
     */
    fun match(line: ImportedLine, candidates: List<Track>): Track? =
        ProviderMatcher.bestMatch(candidates, anchor(line))

    private fun anchor(line: ImportedLine): Track = Track(
        id = 0L,
        name = line.title,
        artists = listOf(line.artist),
        albumName = "",
        albumId = 0L,
        coverUrl = null,
        durationMs = 0L,
    )
}
