package com.yunyin.music

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.playback.LocalPlayerController
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.AppViewModel
import com.yunyin.music.ui.PlayerViewModel
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.MiniPlayer
import com.yunyin.music.ui.components.PlayerTab
import com.yunyin.music.ui.components.QueueSheet
import com.yunyin.music.ui.components.TabBar
import com.yunyin.music.ui.screens.CollectionScreen
import com.yunyin.music.ui.screens.HomeScreen
import com.yunyin.music.ui.screens.LibraryScreen
import com.yunyin.music.ui.screens.LoginScreen
import com.yunyin.music.ui.screens.PlayerScreen
import com.yunyin.music.ui.screens.SearchScreen
import com.yunyin.music.ui.screens.SettingsScreen
import com.yunyin.music.ui.screens.SplashScreen
import com.yunyin.music.ui.theme.AmllTheme
import com.yunyin.music.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * Navigation is a small explicit state machine rather than a nav graph: there are three
 * tabs, two full-screen presentations (player, settings/login) and an optional collection
 * page, so a graph would add indirection without removing a decision. Overlays are
 * animated in and out while the tab content stays mounted, which is what makes the player
 * feel like it rises over the app instead of replacing it.
 */
class MainActivity : ComponentActivity() {

    /** Result of the POST_NOTIFICATIONS request; unused beyond being consumed. */
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.from(this)

        setContent {
            AmllTheme {
                CompositionLocalProvider(LocalPlayerController provides container.player) {
                    YunYinApp(container)
                }
            }
        }
    }

    @Composable
    private fun YunYinApp(container: AppContainer) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val playerViewModel: PlayerViewModel = viewModel(factory = viewModelFactory(container))
        val appViewModel: AppViewModel = viewModel(factory = viewModelFactory(container))

        // Playback state is read through *narrow* derived flows rather than collecting the whole
        // snapshot here.
        //
        // `PlaybackUiState` carries `positionMs`, which the player republishes 4x/second while
        // playing. Collecting it at this level therefore recomposed this entire composable — and
        // with it the home list — four times a second, on top of whatever the user was doing. That
        // is what made scrolling stutter while music played.
        //
        // Anything needing the position (the player screen) collects the full state *inside* its
        // own branch instead, so the invalidation is scoped to the component that actually uses it.
        val playerState = container.player.state

        val hasTrack by remember {
            playerState.map { it.hasTrack }.distinctUntilChanged()
        }.collectAsState(initial = false)

        // Mini player contents. Position/duration are stripped because nothing in the bar shows
        // them, so a bare progress tick must not invalidate it.
        val miniPlayerState by remember {
            playerState.map { it.copy(positionMs = 0L, durationMs = 0L) }.distinctUntilChanged()
        }.collectAsState(initial = PlaybackUiState())

        val playbackError by remember {
            playerState.map { it.error }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val playbackNotice by remember {
            playerState.map { it.notice }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val homeState by appViewModel.home.collectAsState()
        val searchState by appViewModel.search.collectAsState()
        val collectionState by appViewModel.collection.collectAsState()

        var tab by remember { mutableStateOf(PlayerTab.Home) }
        var showPlayer by remember { mutableStateOf(false) }
        var showLyrics by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showLogin by remember { mutableStateOf(false) }
        var account by remember { mutableStateOf(container.settings.account) }

        // Set once the first-launch session work below finishes. The splash waits on it (and on the
        // home feed) so the app is never revealed in a half-initialised state.
        var sessionReady by remember { mutableStateOf(false) }

        // Establish a session on first launch: anonymous, so basic playback works before
        // the user signs in. The library tab is always the way in to a real account.
        LaunchedEffect(Unit) {
            requestNotificationPermission()
            if (container.settings.cookie.isBlank()) {
                when (val result = container.client.registerAnonymous()) {
                    is NetResult.Ok -> account = result.value
                    is NetResult.Err -> account = null
                }
            } else {
                account = (container.client.loginStatus() as? NetResult.Ok)?.value
                    ?: container.settings.account
            }
            // Pull the user's playlists once the real account is known (no-op for guests).
            appViewModel.loadUserPlaylists(account)
            sessionReady = true
        }

        // ------------------------------------------------------------ launch screen
        //
        // Held until the two things a cold start genuinely waits on are done: the session, and the
        // landing tab's feed. The splash is a *cover* for that work, so it is not a fixed delay —
        // but a minimum keeps it from flashing past on a warm start (which reads as a glitch), and a
        // fail-safe cap guarantees a stuck network can never trap the user on it.
        var splashVisible by remember { mutableStateOf(true) }
        val splashStartMs = remember { SystemClock.uptimeMillis() }
        val startupReady = sessionReady && !homeState.loading

        LaunchedEffect(startupReady) {
            if (!startupReady) return@LaunchedEffect
            val elapsed = SystemClock.uptimeMillis() - splashStartMs
            val remaining = (SPLASH_MIN_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            splashVisible = false
        }
        LaunchedEffect(Unit) {
            delay(SPLASH_MAX_MS)
            splashVisible = false
        }

        // The launch ground is the icon's blue while the app itself follows the system theme, so the
        // status bar icons have to flip: light icons on the blue splash, then whatever the theme
        // wants. Without this, a light-mode device shows dark icons on a mid-blue bar — legible, but
        // not what a first-party app would do, and inconsistent with the dark-mode case.
        val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
        DisposableEffect(splashVisible, darkTheme) {
            val window = (context as? android.app.Activity)?.window
            val controller = window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            }
            // `isAppearanceLightStatusBars = true` means DARK icons (a light bar appearance), so
            // the splash wants it false and the app wants it to match the theme.
            controller?.isAppearanceLightStatusBars = !splashVisible && !darkTheme
            onDispose { }
        }

        /**
         * Plays [track] within [queue], expanding the queue so next/prev work.
         *
         * Guarded because this is the app's main user-facing entry point: a failure while
         * building the queue (e.g. the player service is not yet connected) must surface
         * as a message, not take the app down.
         */
        fun play(track: com.yunyin.music.core.Track, queue: List<com.yunyin.music.core.Track>) {
            val safeQueue = queue.ifEmpty { listOf(track) }
            val index = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            runCatching { container.player.playQueue(safeQueue, index) }
                .onSuccess {
                    showPlayer = true
                    // Record locally so the library's "最近播放" reflects real plays.
                    container.playHistory.record(track)
                    appViewModel.refreshRecentTracks()
                }
                .onFailure { error ->
                    android.util.Log.e("YunYin", "playQueue failed for ${track.id}", error)
                    Toast.makeText(context, "播放失败：${error.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                }

            // Member-only/无版权 的提示由 PlayerController 统一处理（它会同时调整循环模式
            // 以免 45 秒试听片段无限循环），这里不再重复提示。
        }

        /** Signs out of the real account and drops back to a guest session. */
        fun signOut() {
            container.settings.clearSession()
            account = null
            showLogin = false
            appViewModel.clearUserPlaylists()
            scope.launch {
                when (val result = container.client.registerAnonymous()) {
                    is NetResult.Ok -> account = result.value
                    is NetResult.Err -> Unit
                }
                appViewModel.refreshHome()
            }
        }

        Box(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
            // ------------------------------------------------ primary content
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    // Cross-fade between tabs: switching previously swapped the whole screen in a
                    // single frame, which read as a flicker.
                    CrossfadeContent(targetState = tab, label = "tab") { activeTab ->
                    when (activeTab) {
                        PlayerTab.Home -> HomeScreen(
                            state = homeState,
                            loader = container.artwork,
                            onSearchClick = { tab = PlayerTab.Search },
                            onTrackClick = { track, queue -> play(track, queue) },
                            onPlaylistClick = { appViewModel.openPlaylist(it) },
                            onChartClick = { id, title, cover ->
                                appViewModel.openChart(id, title, cover)
                            },
                        )

                        PlayerTab.Search -> SearchScreen(
                            state = searchState,
                            loader = container.artwork,
                            onKeywordChange = appViewModel::onKeywordChange,
                            onSubmit = { appViewModel.submitSearch(it) },
                            onClear = { appViewModel.clearSearch() },
                            onRemoveHistory = appViewModel::removeHistory,
                            onTrackClick = { play(it, searchState.results) },
                        )

                        PlayerTab.Library -> LibraryScreen(
                            account = account,
                            playlists = appViewModel.userPlaylists,
                            playlistsLoading = appViewModel.userPlaylistsLoading,
                            recentTracks = appViewModel.recentTracks,
                            loader = container.artwork,
                            onSignIn = { showLogin = true },
                            onSettings = { showSettings = true },
                            onPlaylistClick = { appViewModel.openPlaylist(it) },
                            onLikedSongsClick = {
                                appViewModel.openLikedSongs(
                                    uid = account?.userId ?: 0L,
                                    coverUrl = appViewModel.userPlaylists
                                        .firstOrNull { it.isLikedSongs }?.coverUrl,
                                )
                            },
                            onTrackClick = { play(it, appViewModel.recentTracks) },
                        )
                    }
                    }
                }

                // Mini player hovers above the tab bar whenever something is loaded.
                AnimatedVisibility(
                    visible = hasTrack && !showPlayer,
                    enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically { it / 2 },
                ) {
                    MiniPlayer(
                        state = miniPlayerState,
                        loader = container.artwork,
                        onExpand = { showPlayer = true },
                        onTogglePlay = container.player::togglePlayPause,
                    )
                }

                TabBar(selected = tab, onSelect = { tab = if (showSettings) tab else it; showSettings = false })
            }

            // ------------------------------------------------ overlays
            AnimatedVisibility(
                visible = collectionState != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(160)),
            ) {
                val state = collectionState
                if (state != null) {
                    Surface(Modifier.fillMaxSize(), color = AppTheme.palette.background) {
                        CollectionScreen(
                            state = state,
                            loader = container.artwork,
                            onBack = appViewModel::closeCollection,
                            onTrackClick = { track, index -> play(track, state.tracks) },
                            onPlayAll = {
                                state.tracks.firstOrNull()?.let { play(it, state.tracks) }
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(280)) { it },
            ) {
                SettingsScreen(
                    quality = container.settings.quality,
                    onQualityChange = { container.settings.quality = it },
                    onClearLyricsCache = {
                        container.lyrics.clearCache()
                        Toast.makeText(context, "歌词缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { showSettings = false },
                )
            }

            AnimatedVisibility(
                visible = showLogin,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(280)) { it },
            ) {
                LoginScreen(
                    currentAccount = account,
                    client = container.client,
                    artworkLoader = container.artwork,
                    onSignedIn = {
                        account = container.settings.account
                        showLogin = false
                        appViewModel.refreshHome()
                        appViewModel.loadUserPlaylists(account)
                        Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                    },
                    onSignOut = ::signOut,
                    onDismiss = { showLogin = false },
                )
            }

            // Full-screen player, rising from the bottom and keeping the tab state.
            AnimatedVisibility(
                visible = showPlayer,
                enter = slideInVertically(tween(380)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(320)) { it } + fadeOut(tween(200)),
            ) {
                // Collected here, inside the player's own branch, so the 4x/second position tick
                // invalidates only the player screen and never the browsing tabs behind it.
                val playback by container.player.state.collectAsState()
                PlayerScreen(
                    state = playback,
                    cover = playerViewModel.coverBitmap,
                    palette = playerViewModel.palette,
                    lyrics = playerViewModel.lyrics,
                    lyricsLoading = playerViewModel.lyricsLoading,
                    loader = container.artwork,
                    qualityLabel = qualityLabel(container.settings.quality),
                    showLyrics = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onCollapse = { showPlayer = false },
                    onTogglePlay = container.player::togglePlayPause,
                    onNext = container.player::next,
                    onPrevious = container.player::previous,
                    onSeek = container.player::seekTo,
                    onCycleRepeat = container.player::cycleRepeat,
                    onToggleShuffle = container.player::toggleShuffle,
                    onShowQueue = { showQueue = true },
                    onShowSettings = { showSettings = true; showPlayer = false },
                    lyricOffsetMs = playerViewModel.lyricOffsetMs,
                    onLyricOffsetChange = playerViewModel::setLyricOffset,
                    positionProvider = container.player::positionMsNow,
                )
            }

            AnimatedVisibility(
                visible = showQueue,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
            ) {
                val playback by container.player.state.collectAsState()
                QueueSheet(
                    queue = playback.queue,
                    currentIndex = playback.index,
                    loader = container.artwork,
                    onSelect = { index ->
                        container.player.seekToIndex(index)
                        showQueue = false
                    },
                    onDismiss = { showQueue = false },
                )
            }

            // ------------------------------------------------ launch screen (topmost)
            //
            // The last child, so it starts above every overlay. It fades and scales away rather
            // than cutting: the app content underneath is already composed and laid out, so the
            // reveal is a cross-dissolve onto a finished screen, which is the point of the splash.
            AnimatedVisibility(
                visible = splashVisible,
                enter = fadeIn(tween(1)),
                exit = fadeOut(tween(SPLASH_EXIT_MS)) + scaleOut(
                    tween(SPLASH_EXIT_MS),
                    targetScale = 1.06f,
                ),
            ) {
                SplashScreen(Modifier.fillMaxSize())
            }
        }

        // Android back gesture / button.
        //
        // Handlers are declared least-important-first: with several enabled, the one
        // composed last wins, so this unwinds overlays in the reverse order they opened
        // (queue, then login/settings, then the player, then a detail page, then the tab).
        BackHandler(enabled = tab != PlayerTab.Home) { tab = PlayerTab.Home }
        BackHandler(enabled = collectionState != null) { appViewModel.closeCollection() }
        BackHandler(enabled = showPlayer) { showPlayer = false }
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = showLogin) { showLogin = false }
        BackHandler(enabled = showQueue) { showQueue = false }

        // Surface playback errors once, then clear them.
        LaunchedEffect(playbackError) {
            if (playbackError != null) {
                Toast.makeText(context, playbackError, Toast.LENGTH_SHORT).show()
                container.player.clearError()
            }
        }

        // One-shot notices from the player (member-only preview, unavailable track).
        LaunchedEffect(playbackNotice) {
            if (playbackNotice != null) {
                Toast.makeText(context, playbackNotice, Toast.LENGTH_LONG).show()
                container.player.clearNotice()
            }
        }
    }

    /**
     * Media3 posts the transport notification through a foreground service, which needs
     * POST_NOTIFICATIONS on API 33+. Playback works without it, but the user would lose
     * the lock-screen controls.
     */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Launch-screen timing.
 *
 * [SPLASH_MIN_MS] keeps the splash from flashing past on a warm start — a screen that appears for
 * one frame reads as a glitch, not as a launch. [SPLASH_MAX_MS] is a fail-safe, so a slow or stuck
 * network cannot trap the user on the splash; the app becomes usable regardless. [SPLASH_EXIT_MS] is
 * the reveal's cross-dissolve onto the already-composed app content.
 */
private const val SPLASH_MIN_MS = 1100L
private const val SPLASH_MAX_MS = 6000L
private const val SPLASH_EXIT_MS = 480

/**
 * Minimal `ViewModelProvider.Factory` for the two view models, both container-backed.
 */
private fun viewModelFactory(container: AppContainer) = object : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(PlayerViewModel::class.java) -> PlayerViewModel(container) as T
            modelClass.isAssignableFrom(AppViewModel::class.java) -> AppViewModel(container) as T
            else -> error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}

/**
 * Maps a `/song/url/v1` level flag to the label shown on the player.
 *
 * Note this states what the app *requests*: the service silently caps a free session at
 * a lower tier, and the resolved `br` is what actually plays.
 */
private fun qualityLabel(level: String): String = when (level) {
    "standard" -> "标准音质"
    "higher" -> "较高音质"
    "exhigh" -> "极高音质"
    "lossless" -> "无损音质"
    "hires" -> "Hi-Res"
    else -> "极高音质"
}

