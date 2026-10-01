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
import com.yunyin.music.BuildConfig
import com.yunyin.music.data.together.NtfyTransport
import com.yunyin.music.data.together.RelayTransport
import com.yunyin.music.data.together.TogetherSession
import com.yunyin.music.data.together.TogetherTransport
import com.yunyin.music.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * Listen-together room engine.
     *
     * NetEase's own HTTP API cannot read a peer's playback state (measured: reporting succeeds,
     * reading returns empty — the room runs over an Agora RTC channel), so the read path comes from a
     * transport. Which one is a deployment choice and is picked here:
     *
     *  - a self-hosted relay, when `together.base.url` is set (`server/` in this repository);
     *  - otherwise the free public pub/sub service, which needs nothing deployed.
     */
    val together: TogetherSession by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // The public service needs a scope of its own: its state arrives on a long-lived subscription
        // that must outlive individual calls, and the session's poll loop is the caller.
        val transport: TogetherTransport =
            if (BuildConfig.TOGETHER_BASE_URL.isNotBlank()) RelayTransport() else NtfyTransport(scope)
        TogetherSession(transport = transport, scope = scope)
    }

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
