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
import com.yunyin.music.data.net.EapiClient
import com.yunyin.music.data.together.NeteaseTogetherApi
import com.yunyin.music.BuildConfig
import com.yunyin.music.data.together.MqttTransport
import com.yunyin.music.data.together.NtfyTransport
import com.yunyin.music.data.together.OfficialTogetherTransport
import com.yunyin.music.data.together.RelayTransport
import com.yunyin.music.data.together.TogetherSession
import com.yunyin.music.data.together.TogetherTransport
import com.yunyin.music.data.update.UpdateManager
import com.yunyin.music.playback.PlayerController
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * NetEase's own eapi endpoint, for the one feature the API proxy cannot serve.
     *
     * Listen-together lives here because the proxy does not carry it properly: two of the routes the official
     * protocol needs (`sync/list/command/report`, `heartbeat`) do not exist on the proxy at all, and its read
     * path returns an empty room. The session cookie is read live on each call, so signing in or out takes
     * effect without rebuilding anything.
     */
    val eapi: EapiClient by lazy { EapiClient(cookieProvider = { settings.cookie }) }

    /** Listen-together, spoken the way NetEase's own client speaks it. */
    val togetherApi: NeteaseTogetherApi by lazy { NeteaseTogetherApi(eapi) }

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
     * Self-update, over GitHub Releases.
     *
     * The "cloud" here is the project's own release page: GitHub hosts and serves the APK, so there is no
     * server to run and nothing to pay for. The repository coordinates come from `local.properties`
     * (`update.repo.owner` / `update.repo.name`) rather than being hardcoded, so a fork does not silently
     * offer its users builds published by someone else; when they are blank the feature reports itself as
     * unconfigured instead of failing a check.
     *
     * The scope carries an exception handler for the same reason the listen-together one does: this is
     * long-running background work on the main dispatcher, and a network failure escaping it would be an
     * uncaught exception on the main thread — which kills the process. An updater that can crash the app is
     * worse than no updater, and this app has already died on launch once.
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

    /**
     * Listen-together room engine.
     *
     * **The default is NetEase's own API** — the official feature, with no third-party service and
     * nothing to deploy. That became possible once the API was measured properly: an earlier probe
     * concluded HTTP could not read the peer's playback state, and that conclusion was wrong — it had
     * been taken against a server whose session was **not logged in**, where the same endpoint answers
     * `需要登录`. Against a signed-in server it returns the room's live command (song, position, play
     * state, and a server-assigned sequence that orders commands with no client clocks involved).
     *
     * The hand-rolled alternatives remain available, selected by configuration rather than by fallback,
     * because a transport is a *rendezvous*: two devices on different ones sit in two rooms that both look
     * empty, with nothing on screen to explain why.
     *
     *  - `together.base.url` — a self-hosted relay (`server/together-relay.js`, or the Cloudflare Worker).
     *  - `together.mqtt.host` — a public MQTT broker.
     *  - `together.ntfy.url` — an HTTP pub/sub service.
     */
    val together: TogetherSession by lazy {
        // The scope **must** carry an exception handler.
        //
        // Both the session and the MQTT client run on this one scope, and it is `Main.immediate` — so an
        // exception thrown out of any of those coroutines is an uncaught exception *on the main thread*,
        // which takes the process down. A `SupervisorJob` isolates siblings from each other but does not
        // install a handler, so "the child failed" still means "the app crashed". A transport's reconnect
        // loop is exactly the kind of long-running background work that must never be able to do that.
        //
        // This is the backstop: the session and the transport each turn their own failures into messages,
        // and anything that somehow slips past them is recorded instead of fatal.
        val handler = CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("YunYin/Together", "uncaught in the together scope", throwable)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
        val transport: TogetherTransport = when {
            BuildConfig.TOGETHER_BASE_URL.isNotBlank() -> RelayTransport()
            BuildConfig.TOGETHER_MQTT_HOST.isNotBlank() -> MqttTransport(scope)
            BuildConfig.TOGETHER_NTFY_URL.isNotBlank() -> NtfyTransport(scope)
            else -> OfficialTogetherTransport(
                api = togetherApi,
                ownUid = { settings.account?.userId ?: 0L },
            )
        }
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
