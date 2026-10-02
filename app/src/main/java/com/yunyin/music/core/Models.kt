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

/**
 * A created together-listen room.
 *
 * Both fields are needed to join: the id identifies the room, and the inviter id says whose invite is
 * being accepted. That is why the UI shares them as a pair rather than showing a bare code.
 */
data class TogetherRoomInfo(
    val roomId: String,
    val inviterId: Long,
)

/** One member of a together-listen room, as `/listentogether/status` reports them. */
data class TogetherUser(
    val uid: Long,
    val nickname: String,
    val avatarUrl: String?,
)

/** Who is in the room. */
data class TogetherStatus(
    val inRoom: Boolean,
    val roomId: String,
    val members: List<TogetherUser>,
)

/**
 * The room's playback state — what the *other* member has done.
 *
 * Measured: a room whose only member is the caller reads back as `data: {}` even after that caller
 * issued a command, so this reports the **peer**, never the caller's own echo. That is what makes
 * polling work — and what makes the feature un-demonstrable with a single account.
 *
 * [serverSeq] is assigned by NetEase and increases per command. It is the natural answer to "whose
 * action is newer?", with no client clocks involved — which matters because the two devices' clocks
 * disagree by tens of seconds (measured), so a timestamp comparison would pick the wrong winner.
 */
data class TogetherRemoteState(
    /** Whose command this is; 0 when nobody has acted yet. */
    val userId: Long = 0L,
    val songId: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val serverSeq: Long = 0L,
    /** The room's queue, as published through `sync/list/command`. */
    val queue: List<Long> = emptyList(),
) {
    val hasCommand: Boolean get() = songId > 0L
}

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
