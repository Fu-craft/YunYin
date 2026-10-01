package com.yunyin.music.core

/** A single playable track, normalized from the various NetEase response shapes. */
data class Track(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val albumName: String,
    val albumId: Long,
    val coverUrl: String?,
    val durationMs: Long,
    /** NetEase `fee` flag: 0 free, 1 VIP, 4 paid album, 8 low-quality free. */
    val fee: Int = 0,
) {
    val artistLine: String get() = artists.joinToString(" / ")
}

/** A playlist as shown in the home feed or library. */
data class Playlist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val description: String? = null,
    /** True for NetEase's built-in "我喜欢的音乐" list. */
    val isLikedSongs: Boolean = false,
)

/** A chart / toplist entry. */
data class ChartInfo(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val updateFrequency: String?,
)

/** An artist's identity, as shown on the artist page. */
data class ArtistInfo(
    val id: Long,
    val name: String,
    val coverUrl: String?,
)

/** Signed-in (or anonymous) account. */
data class Account(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?,
    val isAnonymous: Boolean = false,
)

/** Home feed assembled from several endpoints. */
data class HomeFeed(
    val featuredPlaylists: List<Playlist> = emptyList(),
    val dailySongs: List<Track> = emptyList(),
    val recommendedPlaylists: List<Playlist> = emptyList(),
    val newSongs: List<Track> = emptyList(),
    val charts: List<ChartInfo> = emptyList(),
)

/** Raw lyric payload straight from `/lyric/new`. */
data class LyricBundle(
    val id: Long,
    val lrc: String? = null,
    /** Line-level translation, aligned to [lrc]. */
    val translation: String? = null,
    val yrc: String? = null,
    val romanized: String? = null,
    /**
     * Word-by-word translation, aligned to [yrc].
     *
     * Preferred over [translation] for karaoke songs: its timestamps line up exactly with
     * YRC line starts (measured 60/60, 30/30 on real tracks) whereas the LRC-aligned track
     * can be tens to hundreds of ms off and needs fuzzy matching.
     */
    val wordTranslation: String? = null,
    /** Word-by-word transliteration/romanisation, aligned to [yrc]. */
    val wordRomanized: String? = null,
)

/** Result wrapper so callers can distinguish "network failed" from "no data". */
sealed interface NetResult<out T> {
    data class Ok<T>(val value: T) : NetResult<T>
    data class Err(val message: String) : NetResult<Nothing>
}

inline fun <T, R> NetResult<T>.map(transform: (T) -> R): NetResult<R> = when (this) {
    is NetResult.Ok -> NetResult.Ok(transform(value))
    is NetResult.Err -> this
}
