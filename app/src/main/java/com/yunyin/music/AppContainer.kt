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
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceManager
import com.yunyin.music.data.lxsource.LxSourcePlayback
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.data.providers.ProviderLyrics
import com.yunyin.music.data.providers.ProviderPlayback
import com.yunyin.music.data.providers.ProviderTrackStore
import com.yunyin.music.data.providers.tx.TxSearch
import java.io.File
import com.yunyin.music.BuildConfig
import com.yunyin.music.data.update.UpdateManager
import com.yunyin.music.playback.PlayerController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled DI container.
 *
 * The graph is tiny and the app is single-process, so a container with lazy
 * singletons keeps construction explicit without a DI framework.
 */
class AppContainer(val appContext: Context) {

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val client: NeteaseClient by lazy { NeteaseClient(settings, lxLog) }

    /** Process-wide local-music index, filled by [LocalMusicScanner] when the user opens 本地音乐. */
    val localMusic: com.yunyin.music.data.local.LocalMusicStore by lazy {
        com.yunyin.music.data.local.LocalMusicStore()
    }

    val localMusicScanner: com.yunyin.music.data.local.LocalMusicScanner by lazy {
        com.yunyin.music.data.local.LocalMusicScanner(appContext, localMusic)
    }

    /** Memory + 10-minute disk cache of playlist track lists (re-entering a playlist is instant). */
    val playlistTrackCache: com.yunyin.music.data.playlist.PlaylistTrackCache by lazy {
        com.yunyin.music.data.playlist.PlaylistTrackCache(
            java.io.File(appContext.cacheDir, "playlist-tracks"),
        )
    }

    val music: MusicRepository by lazy { MusicRepository(client, lxPlayback, providers, lxLog, localMusic, playlistTrackCache) }

    val extraLyrics: com.yunyin.music.data.providers.extra.ExtraLyricsProvider by lazy {
        com.yunyin.music.data.providers.extra.ExtraLyricsProvider(log = lxLog)
    }

    val lyrics: LyricsRepository by lazy {
        LyricsRepository(appContext, client, providerLyrics, extraLyrics, lxLog)
    }

    val artwork: ArtworkLoader by lazy { ArtworkLoader(appContext) }

    val searchHistory: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }

    val playHistory: PlayHistoryStore by lazy { PlayHistoryStore(appContext) }

    /** Songs liked inside this app, plus the removals that mark cloud likes as un-liked here. */
    val likedSongs: LikedSongsStore by lazy { LikedSongsStore(appContext) }

    /**
     * Single-track downloads.
     *
     * The scope belongs to the container, not to a screen: leaving the player while a long flac downloads must not
     * cancel it. MediaStore writes are plain IO, so a small dedicated client with bounded timeouts is used for both
     * the download stream and the advisory preview probe (no new permission: the app writes only its own
     * `Music/云音` collection).
     */
    val downloads: com.yunyin.music.data.download.TrackDownloadManager by lazy {
        val downloadClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        com.yunyin.music.data.download.TrackDownloadManager(
            resolve = { trackId, quality -> music.streamUrl(trackId, quality) },
            preferredQuality = { settings.quality },
            sink = com.yunyin.music.data.download.MediaStoreAudioWriter(appContext, downloadClient, lxLog),
            log = lxLog,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            probe = { url, duration ->
                val (verdict, length) =
                    com.yunyin.music.data.lxsource.LxPreviewProbe.inspect(url, duration, downloadClient)
                when (verdict) {
                    com.yunyin.music.data.lxsource.LxPreviewProbe.Verdict.FULL ->
                        com.yunyin.music.data.download.DownloadProbeVerdict.FULL to length
                    com.yunyin.music.data.lxsource.LxPreviewProbe.Verdict.PREVIEW_CLIP ->
                        com.yunyin.music.data.download.DownloadProbeVerdict.PREVIEW_CLIP to length
                    com.yunyin.music.data.lxsource.LxPreviewProbe.Verdict.UNKNOWN ->
                        com.yunyin.music.data.download.DownloadProbeVerdict.UNKNOWN to length
                }
            },
        )
    }

    /**
     * The liked rule shared by the heart, the liked list and its count.
     *
     * One object rather than the store being read from three places: the three views have to agree, and
     * keeping the rule here is what makes that structural instead of coincidental.
     */
    val liked: LikedSongsRepository by lazy {
        LikedSongsRepository(
            likedSongs, music, settings, lxLog, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    /** The user's own avatar/background/signature for the profile header. */
    val together: com.yunyin.music.data.together.TogetherSession by lazy {
        com.yunyin.music.data.together.TogetherSession(
            player,
            com.yunyin.music.data.together.TogetherApi(com.yunyin.music.data.together.TogetherTransport(settings), lxLog),
            settings, lxLog, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    val profile: ProfileStore by lazy { ProfileStore(appContext, settings) }

    /** Saves album covers into the device's picture collection. */
    val covers: CoverDownloader by lazy { CoverDownloader(appContext) }

    /** Publishes song/lyrics/playback state to 词幕 (Lyricon), when it is installed. */
    val lyricon: LyriconBridge by lazy { LyriconBridge(appContext, settings) }

    /** Flyme's status-bar lyric, via a resident notification ticker (Flyme-family ROMs only). */
    val tickerLyrics: FlymeLyricNotifier by lazy { FlymeLyricNotifier(appContext, lxLog) }

    /**
     * Imported LX (洛雪) custom sources.
     *
     * A source is a JavaScript file speaking the `globalThis.lx` host API; importing one runs it in the bundled
     * QuickJS engine to prove it initialises, then keeps the script and its metadata under `filesDir/lxsource`.
     */
    val lxSources: LxSourceManager by lazy { LxSourceManager(appContext) }

    /**
     * Diagnostics shared by the custom-source path and the built-in provider path.
     *
     * One instance means the Settings copy row shows both kinds of failure in the order they happened, which is
     * what makes "which of the two paths failed for this song" answerable from one paste.
     */
    val lxLog: LxSourceLog by lazy { LxSourceLog(appContext) }

    /**
     * Resolves playback URLs through an enabled [lxSources] script.
     *
     * Kept hot rather than constructed per lookup: it owns one QuickJS runtime per source and reuses it across
     * track loads, because creating an engine and executing a source takes long enough to be heard at the start
     * of a track.
     */
    val lxPlayback: LxSourcePlayback by lazy {
        LxSourcePlayback(lxSources, lxLog)
    }

    /** Persisted identity of tracks that came from another platform's catalogue (see `ProviderTrackStore`). */
    val providerTracks: ProviderTrackStore by lazy {
        ProviderTrackStore(File(appContext.filesDir, "providers/tracks.json"))
    }

    /** Built-in QQ Music search (pure Kotlin + OkHttp; see `TxSearch` for provenance). */
    val txSearch: TxSearch by lazy { TxSearch(lxLog) }

    /** Cross-platform playback: provider tracks, and the NetEase-VIP retry on another platform. */
    val providers: ProviderPlayback by lazy {
        ProviderPlayback(lxPlayback, txSearch, providerTracks, lxLog)
    }

    /**
     * Lyrics for provider tracks (QQ Music QRC), with the NetEase lyric path as the fallback.
     *
     * Shares [providerTracks] with playback, so a track that was found and played by the provider port can be
     * looked up for lyrics by the same persisted identity.
     */
    val providerLyrics: ProviderLyrics by lazy {
        ProviderLyrics(providerTracks, log = lxLog)
    }

    /**
     * Self-update, over GitHub Releases.
     *
     * The "cloud" here is the project's own release page: GitHub hosts and serves the APK, so there is no
     * server to run and nothing to pay for. The repository coordinates come from `local.properties`
     * (`update.repo.owner` / `update.repo.name`) rather than being hardcoded, so a fork does not silently
     * offer its users builds published by someone else; when they are blank the feature reports itself as
     * unconfigured instead of failing a check.
     *
     * The scope carries an exception handler: this is long-running background work on the main dispatcher,
     * and a network failure escaping it would be an uncaught exception on the main thread — which kills the
     * process. An updater that can crash the app is worse than no updater, and this app has already died on
     * launch once.
     */
    val update: UpdateManager by lazy {
        val handler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("YunYin/Update", "uncaught in the update scope", throwable)
        }
        UpdateManager(
            context = appContext,
            owner = BuildConfig.UPDATE_REPO_OWNER,
            repo = BuildConfig.UPDATE_REPO_NAME,
            currentVersion = BuildConfig.VERSION_NAME,
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler),
        )
    }

    val player: PlayerController by lazy {
        PlayerController(
            context = appContext,
            resolveStream = { trackId -> music.streamUrl(trackId, settings.quality) },
            // A lookup for a *different* track is superseded when the player moves on. The new item's id is passed
            // so a callback that arrives after its load started cannot discard the very lookup it needs — that race
            // produced "Source error" on some tracks.
            onMediaItemChanged = { trackId -> lxPlayback.cancelPending("media_item_transition", trackId) },
            // So the trial notice can say whether the source itself failed, instead of implying the account did.
            sourceFailureNote = { trackId -> lxPlayback.lastOutcome(trackId) },
            // A failed playback drops the cached URL for that track, so an expired CDN token is re-resolved instead
            // of being served from the cache until it ages out.
            onStreamError = { trackId -> music.invalidateResolution(trackId) },
            crossfadeEnabled = { settings.seamlessTransition },
        )
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as YunYinApp).container
    }
}
