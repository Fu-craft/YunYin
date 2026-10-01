package com.yunyin.music

import android.content.Context
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.LikedSongsRepository
import com.yunyin.music.data.LikedSongsStore
import com.yunyin.music.data.LyricsRepository
import com.yunyin.music.data.MusicRepository
import com.yunyin.music.data.PlayHistoryStore
import com.yunyin.music.data.ProfileStore
import com.yunyin.music.data.SearchHistoryStore
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.CoverDownloader
import com.yunyin.music.data.FlymeLyricNotifier
import com.yunyin.music.data.LyriconBridge
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.playback.PlayerController

/**
 * Hand-rolled DI container.
 *
 * The graph is tiny and the app is single-process, so a container with lazy
 * singletons keeps construction explicit without a DI framework.
 */
class AppContainer(val appContext: Context) {

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val client: NeteaseClient by lazy { NeteaseClient(settings) }

    val music: MusicRepository by lazy { MusicRepository(client) }

    val lyrics: LyricsRepository by lazy { LyricsRepository(appContext, client) }

    val artwork: ArtworkLoader by lazy { ArtworkLoader(appContext) }

    val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }

    val playHistory: PlayHistoryStore by lazy { PlayHistoryStore(appContext) }

    /** Songs liked inside this app, plus the removals that mark cloud likes as un-liked here. */
    val likedSongs: LikedSongsStore by lazy { LikedSongsStore(appContext) }

    /**
     * The liked rule shared by the heart, the liked list and its count.
     *
     * One object rather than the store being read from three places: the three views have to agree, and
     * keeping the rule here is what makes that structural instead of coincidental.
     */
    val liked: LikedSongsRepository by lazy { LikedSongsRepository(likedSongs, music) }

    /** The user's own avatar/background/signature for the profile header. */
    val profile: ProfileStore by lazy { ProfileStore(appContext, settings) }

    /** Saves album covers into the device's picture collection. */
    val covers: CoverDownloader by lazy { CoverDownloader(appContext) }

    /** Publishes song/lyrics/playback state to 词幕 (Lyricon), when it is installed. */
    val lyricon: LyriconBridge by lazy { LyriconBridge(appContext, settings) }

    /** Flyme's status-bar lyric, via a resident notification ticker (Flyme-family ROMs only). */
    val tickerLyrics: FlymeLyricNotifier by lazy { FlymeLyricNotifier(appContext) }

    val player: PlayerController by lazy {
        PlayerController(appContext) { trackId ->
            music.streamUrl(trackId, settings.quality)
        }
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as YunYinApp).container
    }
}
