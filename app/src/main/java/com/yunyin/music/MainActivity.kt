package com.yunyin.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.ShareText
import com.yunyin.music.playback.LocalPlayerController
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.AppViewModel
import com.yunyin.music.ui.AudioQuality
import com.yunyin.music.ui.CollectionUiState
import com.yunyin.music.ui.PlayerViewModel
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.FloatingMiniPlayer
import com.yunyin.music.ui.components.FloatingTabBar
import com.yunyin.music.ui.components.PlayerTab
import com.yunyin.music.ui.components.QueueSheet
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunyin.music.ui.screens.CollectionScreen
import com.yunyin.music.ui.screens.HomeScreen
import com.yunyin.music.ui.screens.LibraryScreen
import com.yunyin.music.ui.screens.LoginScreen
import com.yunyin.music.ui.screens.PlayerScreen
import com.yunyin.music.ui.screens.ProfileEditSheet
import com.yunyin.music.ui.screens.PlayerArtworkCorner
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

        // Liking is local, so the library's count has to be re-read when the heart is tapped — the two
        // are separate view models and neither observes the other.
        DisposableEffect(playerViewModel, appViewModel) {
            playerViewModel.onLikedChanged = { appViewModel.refreshLikedCount() }
            onDispose { playerViewModel.onLikedChanged = null }
        }

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

        /**
         * The audio quality the player is set to.
         *
         * Held as Compose state as well as persisted, because it is changed from the player's own chip:
         * a `SharedPreferences` write does not invalidate composition, so a chip bound straight to the
         * stored value would keep showing the old tier after a selection.
         */
        var quality by remember { mutableStateOf(AudioQuality.from(container.settings.quality)) }
        var statusBarLyricsEnabled by remember { mutableStateOf(container.settings.statusBarLyrics) }
        var seamlessTransition by remember { mutableStateOf(container.settings.seamlessTransition) }

        // ------------------------------------------------------------ profile
        var showProfileEdit by remember { mutableStateOf(false) }
        var signature by remember { mutableStateOf(container.settings.profileSignature) }
        var useNeteaseAvatar by remember { mutableStateOf(container.settings.useNeteaseAvatar) }
        // Bumped after a picture is chosen, so the header reloads even though the setting itself is what
        // changed and it is not part of this composition's state.
        var profileRevision by remember { mutableIntStateOf(0) }
        var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
        var backgroundBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        // The photo picker, not `GetContent`: no storage permission is involved and the system provides the
        // UI. Each picker is a separate launcher because the two destinations are separate choices.
        val avatarPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    if (container.profile.setAvatar(context, uri)) {
                        useNeteaseAvatar = false
                        profileRevision++
                    }
                }
            }
        }
        val backgroundPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    if (container.profile.setBackground(context, uri)) profileRevision++
                }
            }
        }

        // Re-resolved whenever a picture is chosen or the header's inputs change. Decoding happens in the
        // loader (off the main thread), so a 4000px photo never blocks the frame that opens the sheet.
        LaunchedEffect(profileRevision, useNeteaseAvatar, showProfileEdit) {
            backgroundBitmap = container.profile.backgroundFile()
                ?.let { container.artwork.loadLocal(it, size = 1080)?.asImageBitmap() }
            avatarBitmap = if (!useNeteaseAvatar) {
                container.profile.avatarFile()
                    ?.let { container.artwork.loadLocal(it, size = 320)?.asImageBitmap() }
            } else {
                null
            }
        }

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
                // Distinguish a definitive "not signed in" from a transport failure.
                //
                // `loginStatus()` returns Ok(null) when the server accepted the request but the cookie
                // carries no profile — i.e. the session has expired. Treating that as "unknown" and
                // falling back to the cached account (the previous behaviour) kept showing the user as
                // signed in and loaded a library the expired cookie could no longer reach. Ok(null) is an
                // answer, so it is honoured: the stale session is cleared and the app asks to sign in
                // again. Only a genuine error keeps the cached account, so a flaky network does not log
                // the user out.
                when (val status = container.client.loginStatus()) {
                    is NetResult.Ok -> {
                        if (status.value == null) {
                            // Expired session: clear it and fall back to a guest session so playback
                            // still works, exactly as an explicit sign-out does.
                            container.settings.clearSession()
                            account = (container.client.registerAnonymous() as? NetResult.Ok)?.value
                        } else {
                            account = status.value
                        }
                    }
                    is NetResult.Err -> account = container.settings.account
                }
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
            /**
             * Records the app's content so the floating chrome can sample it.
             *
             * `layerBackdrop` makes the subtree draw into a shared layer; `drawBackdrop` on the glass
             * then reads it. The chrome itself is deliberately **outside** this subtree — including it
             * would put the bars into the layer they sample, so they would blur themselves.
             */
            val bottomBackdrop = rememberLayerBackdrop()

            // ------------------------------------------------ primary content
            Box(Modifier.fillMaxSize().layerBackdrop(bottomBackdrop)) {
                Box(Modifier.fillMaxSize()) {
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
                            signature = signature,
                            likedCount = appViewModel.likedCount,
                            headerBackground = backgroundBitmap,
                            headerAvatar = avatarBitmap,
                            onSignIn = { showLogin = true },
                            onSettings = { showSettings = true },
                            onEditProfile = { showProfileEdit = true },
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
            }

            /**
             * Floating chrome: frosted-blur panels that float above the content.
             *
             * Overlaid rather than laid out in a column, which is what makes it float — the lists scroll
             * underneath and their colour comes through the material. The bottom padding of each screen
             * keeps its last rows clear of the bars. `bottomBackdrop` records that content and the panels
             * diffuse it, so what shows through is a blurred colour field rather than readable rows.
             */
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = FloatingChromeBottomGap),
                verticalArrangement = Arrangement.spacedBy(FloatingChromeGap),
            ) {
                // Mini player floats directly above the tab bar whenever something is loaded.
                //
                // Kept composed while the player is open: the expand transition grows the player's cover
                // *out of this capsule's artwork*, so the source has to still be measured for the start
                // of that animation to be anchored to it. It does not need hiding — the player screen is
                // drawn after it and is opaque once the transition completes.
                AnimatedVisibility(
                    visible = hasTrack,
                    enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically { it / 2 },
                ) {
                    FloatingMiniPlayer(
                        state = miniPlayerState,
                        loader = container.artwork,
                        backdrop = bottomBackdrop,
                        onExpand = { showPlayer = true },
                        onTogglePlay = container.player::togglePlayPause,
                    )
                }

                FloatingTabBar(
                    selected = tab,
                    onSelect = { tab = if (showSettings) tab else it; showSettings = false },
                    backdrop = bottomBackdrop,
                )
            }

            // ------------------------------------------------ overlays
            // The last non-null collection, kept for the duration of the exit animation.
            //
            // This is what makes the exit visible at all. The content was gated on `collectionState`
            // directly, and closing sets it to null — so the exit transition ran on an *empty* box and the
            // page appeared to vanish instantly, while the enter (which has a non-null state) slid in
            // normally. Holding the previous value means the outgoing page still has something to slide.
            var lastCollection by remember { mutableStateOf<CollectionUiState?>(null) }
            SideEffect { collectionState?.let { lastCollection = it } }

            AnimatedVisibility(
                visible = collectionState != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(160)),
            ) {
                val state = lastCollection
                if (state != null) {
                    // Stripped to just what the list marks: collecting the full state here would
                    // recompose all 500 rows four times a second while music plays.
                    val nowPlaying by remember {
                        playerState
                            .map { it.current?.id to it.isPlaying }
                            .distinctUntilChanged()
                    }.collectAsState(initial = null to false)

                    Surface(Modifier.fillMaxSize(), color = AppTheme.palette.background) {
                        CollectionScreen(
                            state = state,
                            loader = container.artwork,
                            onBack = appViewModel::closeCollection,
                            onTrackClick = { track, index -> play(track, state.tracks) },
                            onPlayAll = {
                                state.tracks.firstOrNull()?.let { play(it, state.tracks) }
                            },
                            onShufflePlay = {
                                // Shuffle is a player mode, not a one-off ordering: enabling it and
                                // then starting anywhere is what makes "next" stay random, whereas
                                // playing a shuffled copy would drift back to the list order.
                                container.player.toggleShuffle()
                                state.tracks.randomOrNull()?.let { play(it, state.tracks) }
                            },
                            onShare = {
                                shareText(ShareText.playlist(state.title, state.id), "分享歌单")
                            },
                            nowPlayingId = nowPlaying.first,
                            isPlaying = nowPlaying.second,
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
                    onClearLyricsCache = {
                        container.lyrics.clearCache()
                        Toast.makeText(context, "歌词缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { showSettings = false },
                    // Read on each entry into Settings rather than held as state: the connection can
                    // change while the app runs (Lyricon installed or started later), and a value
                    // captured once would go stale exactly when the user opens this to check it.
                    lyriconConnected = container.lyricon.connected,
                    lyriconAvailable = container.lyricon.available,
                    onRetryLyricon = { container.lyricon.retry() },
                    flymeSupported = container.tickerLyrics.isSupported(),
                    statusBarLyrics = statusBarLyricsEnabled,
                    onStatusBarLyricsChange = { enabled ->
                        statusBarLyricsEnabled = enabled
                        container.settings.statusBarLyrics = enabled
                        // Turning it off must remove the resident notification immediately rather
                        // than leaving it in the shade until the next track change.
                        if (!enabled) container.tickerLyrics.clear()
                    },
                    seamlessTransition = seamlessTransition,
                    onSeamlessTransitionChange = { enabled ->
                        seamlessTransition = enabled
                        // Writing the preference is the whole action: the service watches this key and
                        // applies it to the running player. Reaching the player from here is not possible
                        // — `Player` and `MediaController` expose no skip-silence control — which is why
                        // the service owns that half.
                        container.settings.seamlessTransition = enabled
                    },
                )
            }

            // The profile editor. Only reachable while signed in, since every control in it writes to the
            // signed-in identity.
            if (showProfileEdit) {
                ProfileEditSheet(
                    nickname = account?.nickname.orEmpty(),
                    signature = signature,
                    useNeteaseAvatar = useNeteaseAvatar,
                    hasCustomAvatar = container.profile.avatarFile() != null,
                    hasCustomBackground = container.profile.backgroundFile() != null,
                    onSignatureChange = { value ->
                        signature = value
                        container.settings.profileSignature = value
                    },
                    onPickAvatar = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickBackground = {
                        backgroundPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClearAvatar = {
                        container.profile.clearAvatar()
                        useNeteaseAvatar = true
                        profileRevision++
                    },
                    onClearBackground = {
                        container.profile.clearBackground()
                        profileRevision++
                    },
                    onUseNeteaseAvatarChange = { enabled ->
                        useNeteaseAvatar = enabled
                        container.settings.useNeteaseAvatar = enabled
                    },
                    onDismiss = { showProfileEdit = false },
                )
            }

            AnimatedVisibility(
                visible = showLogin,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(280)) { it },
            ) {                LoginScreen(
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

            // Full-screen player, sliding up from the bottom.
            //
            // This replaced a "container expand" transition (the mini player growing into the player),
            // which was removed: it was three attempts to make one effect land, and none of them was
            // smooth on the device. A slide is a fraction of the work — no transform, no clip path, no
            // custom gesture coupling — and the app's own presentation switch (artwork ↔ lyrics) already
            // gives the player its motion.
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
                    quality = quality,
                    onQualityChange = { tier ->
                        quality = tier
                        container.settings.quality = tier.level
                        // The stream URL was resolved from the previous quality when the track
                        // loaded, so without reloading it the new tier would not be heard until the
                        // next song — the control would look broken.
                        container.player.applyQualityChange()
                        container.player.refreshCurrentTrial()
                    },
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
                    download = playerViewModel.download,
                    onSaveCover = playerViewModel::saveCover,
                    onDownloadDismissed = playerViewModel::clearDownloadState,
                    liked = playerViewModel.liked,
                    onToggleLike = playerViewModel::toggleLike,
                    onShareTrack = {
                        shareText(playerViewModel.currentShareText(), "分享歌曲")
                    },
                    onCopyLyric = { text ->
                        if (text.isNullOrBlank()) {
                            Toast.makeText(context, "这一行没有可复制的文字", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboard =
                                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("歌词", text),
                            )
                            Toast.makeText(context, "已复制歌词", Toast.LENGTH_SHORT).show()
                        }
                    },
                    positionProvider = container.player::positionMsNow,
                )
            }

            // Transient messages from the player (a refused like, a failed share).
            LaunchedEffect(playerViewModel.notice) {
                val message = playerViewModel.notice ?: return@LaunchedEffect
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                playerViewModel.clearNotice()
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

    /**
     * Hands [text] to whatever app the user picks for sharing.
     *
     * A plain `text/plain` send, not an intent aimed at the NetEase app: the app is a third-party
     * client, so a track has no public URL to hand over and a "name - artist" line is what is
     * actually useful. A playlist does have an addressable page, which is why its text carries the
     * id — see [ShareText].
     */
    private fun shareText(text: String?, chooserTitle: String) {
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "没有可分享的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, chooserTitle))
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
 * Floating chrome metrics.
 *
 * The bars are inset from the bottom so they read as floating rather than docked; [FloatingChromeGap]
 * separates the mini player from the tab bar, and [FloatingChromeBottomGap] leaves the home indicator
 * area clear on top of the navigation-bar inset applied by the caller.
 */
private val FloatingChromeGap = 8.dp
private val FloatingChromeBottomGap = 10.dp

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

