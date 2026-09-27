package com.yunyin.music.data

/**
 * Outgoing share text for the catalog.
 *
 * A playlist's public page is addressed by its id, so the id — not a locally-built deep link — is
 * what makes the link openable by whoever receives it. The liked-songs list has no public page, so
 * it shares as a bare title rather than a link that would 404.
 */
object ShareText {

    /** "《title》" plus the playlist page, or just the title when there is no addressable page. */
    fun playlist(title: String, id: Long): String {
        val name = title.trim()
        val quoted = if (name.isEmpty()) "歌单" else "《$name》"
        return if (id > 0L) "$quoted\n$PLAYLIST_URL_PREFIX$id" else quoted
    }

    private const val PLAYLIST_URL_PREFIX = "https://music.163.com/#/playlist?id="
}
