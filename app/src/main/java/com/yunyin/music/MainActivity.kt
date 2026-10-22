package com.yunyin.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.ShareText
import com.yunyin.music.data.net.BeatModeRules
import com.yunyin.music.data.net.BeatModeSeedRules
import com.yunyin.music.data.net.BeatSeed
import com.yunyin.music.data.net.BeatSeedPath
import com.yunyin.music.playback.LocalPlayerController
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.AppViewModel
import com.yunyin.music.ui.ArtistUiState
import com.yunyin.music.ui.AudioQuality
import com.yunyin.music.ui.CollectionUiState
import com.yunyin.music.ui.PlayerViewModel
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.FloatingChromeBottomGap
import com.yunyin.music.ui.components.FloatingChromeGap
import com.yunyin.music.ui.components.FloatingMiniPlayer
import com.yunyin.music.ui.components.FloatingTabBar
import com.yunyin.music.ui.components.LocalChromeTopRowVisible
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunyin.music.ui.components.PlayerTab
import com.yunyin.music.ui.components.QueueSheet
import com.yunyin.music.ui.components.TabBarInset
import com.yunyin.music.ui.screens.CollectionScreen
import com.yunyin.music.ui.screens.PlaylistSquareScreen
import com.yunyin.music.ui.screens.ArtistScreen
import com.yunyin.music.ui.screens.HomeScreen
import com.yunyin.music.ui.screens.LibraryScreen
import com.yunyin.music.ui.screens.LoginScreen
import com.yunyin.music.ui.screens.PROJECT_URL
import com.yunyin.music.ui.download.DownloadController
import com.yunyin.music.ui.download.LocalDownloadController
import com.yunyin.music.ui.screens.PlaylistConfirmSheet
import com.yunyin.music.ui.screens.PlaylistEditorSheet
import com.yunyin.music.ui.screens.PlaylistPickerSheet
import com.yunyin.music.ui.screens.SleepTimerSheet
import com.yunyin.music.ui.screens.PlayerScreen
import com.yunyin.music.ui.screens.LxSourceUrlSheet
import com.yunyin.music.ui.screens.ProfileEditSheet
import com.yunyin.music.ui.screens.PlayerArtworkCorner
import com.yunyin.music.ui.screens.SearchScreen
import com.yunyin.music.data.lxsource.LxImportResult
import com.yunyin.music.data.lxsource.LxToggleResult
import com.yunyin.music.ui.screens.SettingsScreen
import com.yunyin.music.ui.screens.PlayerStyleSheet
import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.beat.BeatModeActions
import com.yunyin.music.ui.beat.LocalBeatModeActions
import com.yunyin.music.ui.screens.UpdateSheet
import com.yunyin.music.ui.theme.AmllTheme
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * Navigation is a small explicit state machine rather than a nav graph: there are two screen tabs in the
 * bottom bar (home and library) plus the 心动模式 bar action, a search screen reached from the home
 * screen's search box, two full-screen presentations (player, settings/login) and an optional collection
 * page, so a graph would add indirection without removing a decision. Overlays are animated in and out
 * while the tab content stays mounted, which is what makes the player feel like it rises over the app
 * instead of replacing it.
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

        // The mini player's last non-empty state, kept for the duration of its exit animation. The
        // now-playing row is hidden when nothing is playing; without this, clearing the queue would
        // null `current` and the panel would unmount instantly, leaving the row to collapse as an
        // empty strip. Same pattern as the collection overlay's `lastCollection` below.
        var lastMiniPlayerState by remember { mutableStateOf(miniPlayerState) }
        SideEffect { if (hasTrack) lastMiniPlayerState = miniPlayerState }

        val playbackError by remember {
            playerState.map { it.error }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val playbackNotice by remember {
            playerState.map { it.notice }.distinctUntilChanged()
        }.collectAsState(initial = null)

        val homeState by appViewModel.home.collectAsState()
        val searchState by appViewModel.search.collectAsState()
        val collectionState by appViewModel.collection.collectAsState()
        val artistState by appViewModel.artist.collectAsState()
        val commentsState by appViewModel.comments.collectAsState()
        val playlistSquareState by appViewModel.playlistSquare.collectAsState()

        // Collected at the top rather than next to the sheet that uses it: Settings (which shows the
        // "检查更新" row) is composed before the sheet, so a value read further down would not be in scope
        // where it is first needed.
        val updateState by container.update.state.collectAsState()

        var tab by remember { mutableStateOf(PlayerTab.Home) }
        var showPlayer by remember { mutableStateOf(false) }
        // The playlist the current playback queue was started from, when it was started from one. It is
        // what 心动模式 uses for `pid` when the player is the entry point; null means "not a playlist"
        // and the fallback is the liked-songs playlist (or no pid at all).
        var lastPlaylistId by remember { mutableStateOf<Long?>(null) }
        var showLyrics by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        // True while the playback queue is a 心动模式 queue. The bar's 心动模式 slot uses it to show the
        // queue that already exists instead of spending another request; any normal play clears it.
        var beatModeActive by remember { mutableStateOf(false) }
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
        // Read from the bridge rather than the preference: the bridge is the authority on whether it is
        // publishing, and it mirrors the stored value, so the switch cannot disagree with what is running.
        var lyriconEnabled by remember { mutableStateOf(container.lyricon.enabled) }
        var seamlessTransition by remember { mutableStateOf(container.settings.seamlessTransition) }
        var crossfadeSeconds by remember { mutableStateOf(container.settings.crossfadeSeconds) }
        var autoImmersiveLyrics by remember { mutableStateOf(container.settings.autoImmersiveLyrics) }
        // The three now-playing background switches. Compose state + the store, same arrangement as
        // the other settings: the player reads the state so a toggle takes effect immediately, and
        // the store makes it survive a restart.
        var nowPlayingCoverBlurBackground by remember {
            mutableStateOf(container.settings.nowPlayingCoverBlurBackground)
        }
        var nowPlayingAudioReactive by remember {
            mutableStateOf(container.settings.nowPlayingAudioReactive)
        }
        var nowPlayingDynamicBackground by remember {
            mutableStateOf(container.settings.nowPlayingDynamicBackground)
        }
        // The player's centrepiece. Held as Compose state as well as persisted, for the same reason as the
        // settings above: a SharedPreferences write does not invalidate composition, so a value read
        // straight from the store would change only on the next launch.
        var playerStyle by remember { mutableStateOf(PlayerStyle.from(container.settings.playerStyle)) }
        var showPlayerStyle by remember { mutableStateOf(false) }

        // ------------------------------------------------------------ playlist management
        var showPlaylistEditor by remember { mutableStateOf(false) }
        var playlistEditorTarget by remember { mutableStateOf<com.yunyin.music.core.Playlist?>(null) }
        var playlistEditorBusy by remember { mutableStateOf(false) }
        var playlistEditorMessage by remember { mutableStateOf<String?>(null) }
        var showPlaylistPicker by remember { mutableStateOf(false) }
        var playlistPickerTrack by remember { mutableStateOf<com.yunyin.music.core.Track?>(null) }
        var playlistPickerPlaylists by remember { mutableStateOf<List<com.yunyin.music.core.Playlist>>(emptyList()) }
        var playlistPickerLoading by remember { mutableStateOf(false) }
        var playlistPickerError by remember { mutableStateOf<String?>(null) }
        var pendingDeletePlaylist by remember { mutableStateOf<com.yunyin.music.core.Playlist?>(null) }
        var pendingRemoveTrack by remember { mutableStateOf<com.yunyin.music.core.Track?>(null) }
        var pendingUnlikeTrack by remember { mutableStateOf<com.yunyin.music.core.Track?>(null) }
        var playlistWriteBusy by remember { mutableStateOf(false) }
        // Host-driven exits: an async save/write/import finishing asks the sheet to leave through its
        // own exit animation; the host keeps the sheet composed until its onDismissed callback.
        var playlistEditorCloseRequest by remember { mutableStateOf(false) }
        var deleteCloseRequest by remember { mutableStateOf(false) }
        var removeCloseRequest by remember { mutableStateOf(false) }
        var unlikeCloseRequest by remember { mutableStateOf(false) }
        var lxUrlCloseRequest by remember { mutableStateOf(false) }
        var showSleepTimer by remember { mutableStateOf(false) }
        val sleepTimerEnd by container.player.sleepTimerEnd.collectAsState()
        val sleepTimerEndOfTrack by container.player.sleepTimerEndOfTrack.collectAsState()

        fun toast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        fun currentUid(): Long = account?.takeIf { !it.isAnonymous }?.userId ?: 0L

        fun openPlaylistCreate() {
            val uid = currentUid()
            if (uid <= 0L) {
                toast(com.yunyin.music.data.playlist.PlaylistOwnership.loginMessage(0L))
                return
            }
            playlistEditorTarget = null
            playlistEditorMessage = null
            playlistEditorCloseRequest = false
            showPlaylistEditor = true
        }

        fun openPlaylistEdit(target: com.yunyin.music.core.Playlist) {
            playlistEditorTarget = target
            playlistEditorMessage = null
            playlistEditorCloseRequest = false
            showPlaylistEditor = true
        }

        fun submitPlaylist(name: String, description: String) {
            val uid = currentUid()
            if (uid <= 0L) {
                playlistEditorMessage = com.yunyin.music.data.playlist.PlaylistOwnership.loginMessage(0L)
                return
            }
            val invalid = com.yunyin.music.data.playlist.PlaylistWrites.validateName(name)
                ?: com.yunyin.music.data.playlist.PlaylistWrites.validateDescription(description)
            if (invalid != null) {
                playlistEditorMessage = invalid
                return
            }
            if (playlistEditorBusy) return
            playlistEditorBusy = true
            playlistEditorMessage = null
            val target = playlistEditorTarget
            scope.launch {
                try {
                    val result: com.yunyin.music.core.NetResult<*> =
                        if (target == null) {
                            container.music.createPlaylist(name, description)
                        } else {
                            container.music.updatePlaylist(target.id, name, description)
                        }
                    when (result) {
                        is com.yunyin.music.core.NetResult.Ok -> {
                            toast(if (target == null) "已创建歌单「${name.trim()}」" else "已保存歌单「${name.trim()}」")
                            appViewModel.refreshUserPlaylists(account)
                            if (target != null) appViewModel.refreshCollection()
                            // Stay composed for the exit animation; onDismissed clears the flag.
                            playlistEditorCloseRequest = true
                        }
                        is com.yunyin.music.core.NetResult.Err -> playlistEditorMessage = result.message
                    }
                } catch (t: Throwable) {
                    playlistEditorMessage = "操作失败：${(t.message ?: t.javaClass.simpleName).take(120)}"
                } finally {
                    playlistEditorBusy = false
                }
            }
        }

        fun openPlaylistPicker(track: com.yunyin.music.core.Track) {
            playlistPickerTrack = track
            playlistPickerPlaylists = emptyList()
            playlistPickerError = null
            showPlaylistPicker = true
            val uid = currentUid()
            if (uid <= 0L) {
                playlistPickerError = com.yunyin.music.data.playlist.PlaylistOwnership.loginMessage(0L)
                return
            }
            playlistPickerLoading = true
            scope.launch {
                try {
                    when (val result = container.music.userPlaylists(uid)) {
                        is com.yunyin.music.core.NetResult.Ok ->
                            playlistPickerPlaylists = result.value.filterNot { it.isLikedSongs }
                        is com.yunyin.music.core.NetResult.Err -> playlistPickerError = result.message
                    }
                } catch (t: Throwable) {
                    playlistPickerError = "加载歌单失败：${(t.message ?: t.javaClass.simpleName).take(120)}"
                } finally {
                    playlistPickerLoading = false
                }
            }
        }

        fun addTrackToPlaylist(target: com.yunyin.music.core.Playlist) {
            val track = playlistPickerTrack ?: return
            if (playlistWriteBusy) return
            playlistWriteBusy = true
            scope.launch {
                try {
                    when (val result = container.music.addTracksToPlaylist(target.id, listOf(track.id))) {
                        is com.yunyin.music.core.NetResult.Ok -> {
                            toast("已添加到「${target.name}」")
                            showPlaylistPicker = false
                            if (collectionState?.id == target.id) appViewModel.refreshCollection()
                        }
                        is com.yunyin.music.core.NetResult.Err -> {
                            playlistPickerError = result.message
                            toast(result.message)
                        }
                    }
                } catch (t: Throwable) {
                    val message = "添加失败：${(t.message ?: t.javaClass.simpleName).take(120)}"
                    playlistPickerError = message
                    toast(message)
                } finally {
                    playlistWriteBusy = false
                }
            }
        }

        fun deletePlaylistNow() {
            val target = pendingDeletePlaylist ?: return
            if (playlistWriteBusy) return
            playlistWriteBusy = true
            scope.launch {
                try {
                    when (val result = container.music.deletePlaylist(target.id)) {
                        is com.yunyin.music.core.NetResult.Ok -> {
                            toast("已删除歌单")
                            deleteCloseRequest = true
                            appViewModel.closeCollection()
                            appViewModel.refreshUserPlaylists(account)
                        }
                        is com.yunyin.music.core.NetResult.Err -> {
                            toast(result.message)
                            deleteCloseRequest = true
                        }
                    }
                } catch (t: Throwable) {
                    toast("删除失败：${(t.message ?: t.javaClass.simpleName).take(120)}")
                    deleteCloseRequest = true
                } finally {
                    playlistWriteBusy = false
                }
            }
        }

        fun removeTrackNow() {
            val track = pendingRemoveTrack ?: return
            val playlistId = collectionState?.id ?: return
            if (playlistWriteBusy) return
            playlistWriteBusy = true
            scope.launch {
                try {
                    when (val result = container.music.removeTracksFromPlaylist(playlistId, listOf(track.id))) {
                        is com.yunyin.music.core.NetResult.Ok -> {
                            toast("已移除")
                            removeCloseRequest = true
                            appViewModel.refreshCollection()
                        }
                        is com.yunyin.music.core.NetResult.Err -> {
                            toast(result.message)
                            removeCloseRequest = true
                        }
                    }
                } catch (t: Throwable) {
                    toast("移除失败：${(t.message ?: t.javaClass.simpleName).take(120)}")
                    removeCloseRequest = true
                } finally {
                    playlistWriteBusy = false
                }
            }
        }

        /** 取消喜欢 from the liked list: the same local-first toggle the player's heart uses. */
        fun removeLikedNow() {
            val track = pendingUnlikeTrack ?: return
            runCatching {
                if (playerViewModel.currentTrack()?.id == track.id) {
                    // The player's own toggle updates its visible heart and notifies the library count.
                    playerViewModel.toggleLike()
                } else {
                    container.liked.toggle(track)
                }
                appViewModel.refreshLikedCount()
                appViewModel.refreshCollection()
                toast("已取消喜欢")
            }.onFailure { t ->
                toast("取消喜欢失败：${(t.message ?: t.javaClass.simpleName).take(120)}")
            }
            unlikeCloseRequest = true
        }

        fun pickSleepTimer(option: Int?) {
            container.player.setSleepTimer(option)
            showSleepTimer = false
            Toast.makeText(
                context,
                when (option) {
                    null, com.yunyin.music.playback.PlaybackSleepTimerRules.OFF -> "已关闭定时关闭"
                    com.yunyin.music.playback.PlaybackSleepTimerRules.END_OF_TRACK -> "播完当前歌后暂停"
                    else -> "将在 $option 分钟后暂停播放"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }

        // ------------------------------------------------------------ profile
        var showProfileEdit by remember { mutableStateOf(false) }
        var signature by remember { mutableStateOf(container.settings.profileSignature) }
        var useNeteaseAvatar by remember { mutableStateOf(container.settings.useNeteaseAvatar) }
        // The displayed name follows the same arrangement as the avatar: the account's own, unless the user
        // has set a local override. Both are presentation, so neither needs an account.
        var useNeteaseName by remember { mutableStateOf(container.settings.useNeteaseName) }
        var customNickname by remember { mutableStateOf(container.settings.customNickname) }

        /** What the profile header shows: the override when set, otherwise the account's own. */
        val displayName = when {
            !useNeteaseName && customNickname.isNotBlank() -> customNickname
            else -> account?.nickname.orEmpty()
        }
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

        // ------------------------------------------------------------ 自定义音源
        //
        // Imported sources are a small list read once and refreshed after every change. The import itself is a
        // suspend call: it runs the picked script in the JS engine and waits for its `inited` event, which is
        // the only way to know the source actually works. `OpenDocument` gives a readable content:// URI with no
        // storage permission, the same arrangement the photo pickers above use.
        var lxSources by remember { mutableStateOf(container.lxSources.list()) }
        var lxImporting by remember { mutableStateOf(false) }
        var lxImportMessage by remember { mutableStateOf<String?>(null) }
        // 从链接导入 dialog state: the URL being typed and whether the dialog is open. Kept out of
        // LxSourceUrlSheet so the dialog stays a pure function of its inputs.
        var showLxUrlSheet by remember { mutableStateOf(false) }
        var lxUrlDraft by remember { mutableStateOf("") }
        fun lxResultMessage(result: LxImportResult): String = when (result) {
            is LxImportResult.Success -> {
                val imported = "已导入「${result.source.header.name}」"
                val key = result.collision
                val base = if (key == null) {
                    imported
                } else {
                    "$imported，但 ${key.wire} 已被「${result.collidedWith ?: "另一个脚本"}」占用，未启用"
                }
                // Say when a mirror delivered the script: the origin row below still shows the pasted address.
                result.viaMirror?.let { "$base（已通过镜像导入：$it）" } ?: base
            }

            is LxImportResult.Failure -> result.message
        }

        fun importLxFromUrl() {
            if (lxImporting) return
            val url = lxUrlDraft.trim()
            lxImporting = true
            lxImportMessage = null
            scope.launch {
                // Policy, download and validation all live in the manager: the dialog only supplies the URL.
                // The manager itself is already a "return an outcome, never throw" boundary; this second guard is
                // here because the import runs from a settings tap — an exception escaping the coroutine is an app
                // crash, and there is no reason to risk that for a download.
                try {
                    val result = container.lxSources.importFromUrl(url)
                    lxImportMessage = lxResultMessage(result)
                    if (result is LxImportResult.Success) {
                        // Only a successful import closes the dialog; a failure keeps the URL there to
                        // fix. The exit animates first — the host clears it from onDismissed.
                        lxUrlCloseRequest = true
                    }
                } catch (t: Throwable) {
                    lxImportMessage = "导入时发生内部错误：${(t.message ?: t.javaClass.simpleName).take(120)}"
                    runCatching {
                        container.lxLog.record("从链接导入异常", t.stackTraceToString())
                    }
                } finally {
                    lxImporting = false
                    // Listing is a file read; if it fails the old list stays on screen instead of crashing.
                    runCatching { lxSources = container.lxSources.list() }
                }
            }
        }
        val lxScriptPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                lxImporting = true
                lxImportMessage = null
                scope.launch {
                    val result = container.lxSources.import(uri)
                    lxImporting = false
                    lxImportMessage = lxResultMessage(result)
                    lxSources = container.lxSources.list()
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
        }

        // No in-app launch surface: the platform splash (white + the launcher mark) stays until the
        // first Compose frame, which is already the real UI. The status bar therefore has to match
        // the theme from that first frame; `isAppearanceLightStatusBars = true` means DARK icons (a
        // light bar appearance), which is the right choice for the white window background.
        LaunchedEffect(Unit) {
            container.liked.onSyncFailure = {
                Toast.makeText(context, "同步失败，已保存在本地", Toast.LENGTH_SHORT).show()
            }
            appViewModel.onSubscribeFailure = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
        DisposableEffect(darkTheme) {
            val window = (context as? android.app.Activity)?.window
            val controller = window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            }
            controller?.isAppearanceLightStatusBars = !darkTheme
            onDispose { }
        }

        /**
         * Plays [track] within [queue], expanding the queue so next/prev work.
         *
         * Guarded because this is the app's main user-facing entry point: a failure while
         * building the queue (e.g. the player service is not yet connected) must surface
         * as a message, not take the app down.
         */
        fun play(
            track: com.yunyin.music.core.Track,
            queue: List<com.yunyin.music.core.Track>,
            /** The playlist this queue came from, for 心动模式's `pid`; null when it did not come from one. */
            playlistId: Long? = null,
        ): Boolean {
            appViewModel.stopFm()
            val safeQueue = queue.ifEmpty { listOf(track) }
            val index = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            var started = false
            runCatching { container.player.playQueue(safeQueue, index) }
                .onSuccess {
                    started = true
                    showPlayer = true
                    lastPlaylistId = playlistId
                    // A normal queue replacement leaves beat mode; 心动模式's own path sets this back on
                    // after this returns, so the bar slot shows the queue that exits.
                    beatModeActive = false
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
            return started
        }

        /**
         * Starts 心动模式.
         *
         * The seed is chosen so the mode also works with **nothing playing**: the current track first
         * (more of this song), then the merged liked list, then `/recommend/songs`. With a seed in hand
         * the flow is exactly the existing one: `/playmode/intelligence/list` ids -> `/song/detail`
         * hydration -> queue replacement -> playback starts on the seed.
         *
         * A guest (no cookie) is refused before any request. If no seed exists at all the user gets the
         * specific [BeatModeRules.NO_SOURCE_MESSAGE] instead of a silent no-op; the chosen path and id are
         * recorded under the existing `stage=beat-mode` diagnostics so a report can be diagnosed.
         *
         * On success the playback queue is **replaced** and playback starts on the seed. On any failure
         * the current queue is untouched and the specific reason is toasted (guest / expired / server
         * message / hydration failure). Replacing through [play] keeps the existing guarantees — history,
         * downloads, lyrics, and the sleep timer applying to the new queue.
         *
         * [onStarted] runs only after the new queue has actually started, so an entry point that also
         * wants to *show* the queue (the bar's 心动模式 slot) opens it on success and stays put on failure.
         */
        fun startBeatMode(explicitPlaylistId: Long?, onStarted: () -> Unit = {}) {
            // Guest first: no cookie means no request at all (the bar is already dimmed for them).
            if (container.settings.cookie.isBlank()) {
                toast(BeatModeRules.GUEST_MESSAGE)
                return
            }
            // Immediate acknowledgement. Everything below is network-bound, and the old version
            // showed nothing until the whole chain finished, so an idle tap was indistinguishable
            // from a dead control even though the seed logic did run.
            toast(BeatModeRules.STARTING_MESSAGE)
            val current = container.player.state.value.current
            val likedPlaylistId = appViewModel.userPlaylists.firstOrNull { it.isLikedSongs }?.id
            val knownPlaylistId = BeatModeRules.resolvePlaylistId(
                explicitPlaylistId = explicitPlaylistId,
                lastPlaylistId = lastPlaylistId,
                likedPlaylistId = likedPlaylistId,
            )
            scope.launch {
                runCatching {
                    container.lxLog.recordDiagnostic(
                        "stage=beat-mode start current=${current?.id ?: "-"}",
                    )
                    // The liked playlist is the documented `pid` fallback, and the library may not have
                    // loaded the user's playlists yet, so fetch them once when the id is still unknown.
                    var resolvedLikedPlaylistId = likedPlaylistId
                    if (resolvedLikedPlaylistId == null) {
                        val uid = currentUid()
                        if (uid > 0L) {
                            when (val lists = container.music.userPlaylists(uid)) {
                                is NetResult.Ok ->
                                    resolvedLikedPlaylistId =
                                        lists.value.firstOrNull { it.isLikedSongs }?.id
                                is NetResult.Err -> Unit
                            }
                        }
                    }

                    // Seed priority: current > liked > daily recommendations. Each step is only fetched
                    // when the previous one has no usable track, so a playing song costs no extra request.
                    var chosen: BeatSeed? = BeatModeSeedRules.choose(current, emptyList(), emptyList())
                    if (chosen == null) {
                        // Local additions first (already in memory), then the cloud half by **ids
                        // only**, hydrating just the first id. Hydrating the whole liked library
                        // (liked ids + one detail batch per 100 songs) here is what made an idle tap
                        // sit silently for many seconds before the beat request could even start.
                        val localLiked = runCatching { container.liked.tracks(0) }
                            .getOrDefault(emptyList())
                        chosen = BeatModeSeedRules.choose(null, localLiked, emptyList())
                        if (chosen == null && currentUid() > 0L) {
                            val firstCloudLiked = runCatching {
                                val ids = when (val result = container.client.likedSongIds(currentUid())) {
                                    is NetResult.Ok -> result.value
                                    is NetResult.Err -> emptyList()
                                }
                                val firstId = ids.firstOrNull { it > 0L } ?: return@runCatching null
                                when (val result = container.client.songsByIds(listOf(firstId))) {
                                    is NetResult.Ok -> result.value.firstOrNull()
                                    is NetResult.Err -> null
                                }
                            }.getOrNull()
                            chosen = BeatModeSeedRules.choose(
                                current = null,
                                liked = listOfNotNull(firstCloudLiked),
                                recommended = emptyList(),
                            )
                        }
                    }
                    if (chosen == null) {
                        // `/recommend/songs`: verified live on the deployed instance (HTTP 200 anonymous).
                        val daily = when (val result = container.client.dailySongs()) {
                            is NetResult.Ok -> result.value
                            is NetResult.Err -> {
                                android.util.Log.i(
                                    "YunYin/BeatMode",
                                    "daily seed fetch failed: ${result.message}",
                                )
                                emptyList()
                            }
                        }
                        chosen = BeatModeSeedRules.choose(null, emptyList(), daily)
                    }
                    val seed = chosen
                    if (seed == null) {
                        container.lxLog.recordDiagnostic("stage=beat-mode seed=none")
                        toast(BeatModeRules.NO_SOURCE_MESSAGE)
                        return@runCatching
                    }
                    // `pid`: the caller's playlist / queue origin for a current-track seed; the liked
                    // playlist for a liked/daily seed, omitted when unknown (the server's answer is then
                    // reported verbatim).
                    val playlistId = when (seed.path) {
                        BeatSeedPath.Current -> knownPlaylistId
                        BeatSeedPath.Liked, BeatSeedPath.Recommend -> resolvedLikedPlaylistId
                    }
                    container.lxLog.recordDiagnostic(
                        "stage=beat-mode seed=${seed.path.label} id=${seed.track.id} " +
                            "pid=${playlistId ?: "-"}",
                    )

                    when (val result = container.music.beatMode(seed.track.id, seed.track.id, playlistId)) {
                        is NetResult.Ok -> {
                            val queue = BeatModeRules.queueFor(result.value, seed.track, seed.track.id)
                            // A queue row must never be id-only: refuse to install a queue that fails the
                            // display invariant instead of showing blank rows (the v3.38.0 bug).
                            val installable = queue.isNotEmpty() && queue.all(BeatModeRules::isDisplayable)
                            if (!installable) {
                                android.util.Log.i(
                                    "YunYin/BeatMode",
                                    "refusing queue: rows=${queue.size} displayable=" +
                                        queue.count(BeatModeRules::isDisplayable),
                                )
                                toast(BeatModeRules.HYDRATION_MESSAGE)
                            } else {
                                val started = play(seed.track, queue)
                                if (started) {
                                    beatModeActive = true
                                    toast("已开启心动模式")
                                    onStarted()
                                }
                            }
                        }
                        is NetResult.Err -> toast(result.message)
                    }
                }.onFailure { error ->
                    android.util.Log.e("YunYin", "beat mode failed", error)
                    toast("心动模式启动失败：${error.message ?: "未知错误"}")
                }
            }
        }

        /**
         * The bar's 心动模式 slot.
         *
         * The resulting queue is the point of the entry: with no beat-mode queue installed the host starts
         * it through the same [startBeatMode] the player and playlist sheets use (no second fetch path),
         * and opens the playback queue once the replacement has actually started. When a beat-mode queue
         * is already live the slot opens **that** queue instead of fetching again — the tap is a view of
         * the mode, not a refresh button. A guest is answered with 请先登录 here, before any request.
         */
        fun openBeatMode() {
            if (container.settings.cookie.isBlank()) {
                toast(BeatModeRules.GUEST_MESSAGE)
                return
            }
            // Only a *live* beat-mode queue is a view to show: with nothing loaded there is no queue
            // to display, so a stale flag must fall through and start a fresh 心动模式 instead of
            // opening an empty sheet — that empty sheet, with no other feedback, is exactly the
            // "no reaction at all" the user reported.
            if (beatModeActive && container.player.state.value.hasTrack) {
                toast("已开启心动模式")
                showQueue = true
                return
            }
            startBeatMode(explicitPlaylistId = null, onStarted = { showQueue = true })
        }

        // 心动模式 entry points: the player's action sheet passes null (the host falls back to the queue's
        // origin / liked playlist), the playlist page passes its own id. Rebuilt when the session changes
        // so a guest row flips to the real action as soon as a cookie exists.
        val beatModeActions = BeatModeActions(
            signedIn = container.settings.cookie.isNotBlank(),
            start = { playlistId -> startBeatMode(playlistId) },
        )

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

        // The glass's pixel source: a Compose layer recorded from the page content — `LayerBackdrop`, the
        // library's documented approach (kyant.gitbook.io/backdrop, "Glass Bottom Bar"). The chrome is
        // composed **outside** that layer, so it can never sample itself, and no draw gate is needed: a gate
        // was tried, Compose baked its "draw nothing" into the display list, and the bar went blank.
        // The documented form (kyant.gitbook.io/backdrop, "Glass Bottom Bar", step 2):
        //
        //     rememberLayerBackdrop { drawRect(backgroundColor); drawContent() }
        //
        // The background **must be painted into the backdrop**. Ours used to live on an ancestor Box, i.e.
        // outside the recorded layer, so everywhere the content did not paint the layer was transparent — and
        // a RenderEffect over a transparent layer produces nothing to see, which is what "the blur does
        // nothing" looks like from the outside. Drawing the page colour first gives the blur an actual field.
        val pageBackground = AppTheme.palette.background
        val bottomBackdrop = rememberLayerBackdrop {
            drawRect(pageBackground)
            drawContent()
        }


        Box(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
            // ------------------------------------------------ primary content
            Box(Modifier.fillMaxSize().layerBackdrop(bottomBackdrop)) {
                // The chrome's now-playing row exists only while a track is loaded (see the chrome block
                // below); the screens read that through `chromeContentBottomPadding()`, which then reserves
                // one row instead of two. Provided here, around the tab screens, because that is where the
                // padding is read.
                CompositionLocalProvider(LocalChromeTopRowVisible provides hasTrack) {
                Box(Modifier.fillMaxSize()) {
                    // Cross-fade between screens. Kept — an instant swap read as a flicker — but
                    // short: switching is a tens-of-times-a-day action, so it must not read as a
                    // transition. 120ms in / 60ms out keeps the overlap of two screens below the
                    // point where two artworks are legibly superimposed.
                    // 歌单广场 loads when the tab is first opened, not at app start: the catalogue
                    // is only needed then, and the view model caches it for the session so
                    // re-entering the tab costs nothing.
                    LaunchedEffect(tab) {
                        if (tab == PlayerTab.PlaylistSquare) appViewModel.openPlaylistSquare()
                    }
                    CrossfadeContent(
                                            targetState = tab,
                                            durationMillis = com.yunyin.music.ui.theme.Motion.TabCrossfadeDuration,
                                            label = "tab",
                                        ) { activeTab ->
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
                            onRetry = appViewModel::refreshHome,
                            // 私人 FM is a first-class listen action, not a settings toggle: the row sits
                            // at the top of Home and the player opens on the first batch.
                            onStartFm = {
                                appViewModel.startFm()
                                showPlayer = true
                            },
                        )

                        PlayerTab.Search -> SearchScreen(
                            state = searchState,
                            loader = container.artwork,
                            onKeywordChange = appViewModel::onKeywordChange,
                            onSubmit = { appViewModel.submitSearch(it) },
                            onClear = { appViewModel.clearSearch() },
                            // Empty-query 取消 dismisses the search child back to Home through the
                            // existing tab state (no new navigation mechanism).
                            onCancel = { appViewModel.clearSearch(); tab = PlayerTab.Home },
                            onRemoveHistory = appViewModel::removeHistory,
                            onTrackClick = { play(it, searchState.results) },
                            onLoadMore = appViewModel::loadMoreSearch,
                            onSuggestionSong = { track -> play(track, listOf(track)) },
                            onSuggestionArtist = { _, name -> appViewModel.openArtistByName(name) },
                            onSuggestionAlbum = { id, name -> appViewModel.openAlbum(id, name, null) },
                            onDismissSuggestions = { appViewModel.onKeywordChange("") },
                        )

                        PlayerTab.PlaylistSquare -> PlaylistSquareScreen(
                            state = playlistSquareState,
                            loader = container.artwork,
                            onCategorySelected = appViewModel::selectPlaylistCategory,
                            onLoadMore = appViewModel::loadMorePlaylistSquare,
                            onRefresh = appViewModel::refreshPlaylistSquare,
                            onPlaylistClick = { playlist ->
                                appViewModel.openPlaylist(
                                    com.yunyin.music.core.Playlist(
                                        id = playlist.id,
                                        name = playlist.name,
                                        coverUrl = playlist.coverUrl,
                                        trackCount = playlist.trackCount,
                                        playCount = playlist.playCount,
                                        description = playlist.description,
                                    ),
                                )
                            },
                        )

                        PlayerTab.Library -> LibraryScreen(
                            account = account,
                            playlists = appViewModel.userPlaylists,
                            playlistsLoading = appViewModel.userPlaylistsLoading,
                            recentTracks = appViewModel.recentTracks,
                            loader = container.artwork,
                            signature = signature,
                            displayName = displayName,
                            likedCount = appViewModel.likedCount,
                            headerBackground = backgroundBitmap,
                            headerAvatar = avatarBitmap,
                            onSignIn = { showLogin = true },
                            onSettings = { showSettings = true },
                            onEditProfile = { showProfileEdit = true },
                            onPlaylistClick = { appViewModel.openPlaylist(it, account?.userId ?: 0L) },
                            onCreatePlaylist = { openPlaylistCreate() },
                            onLikedSongsClick = {
                                appViewModel.openLikedSongs(
                                    uid = account?.userId ?: 0L,
                                    coverUrl = appViewModel.userPlaylists
                                        .firstOrNull { it.isLikedSongs }?.coverUrl,
                                )
                            },
                            onTrackClick = { play(it, appViewModel.recentTracks) },
                            onPlayLocalTracks = { list, index ->
                                list.getOrNull(index)?.let { play(it, list) }
                            },
                        )
                    }
                    }
                }
                }
            }

            /**
             * Floating chrome: liquid-glass panels that float above the content.
             *
             * The tab row is always present. The now-playing capsule above it is composed **only while a
             * track is loaded** — no "未在播放" placeholder holds an empty row — and both rows are
             * [FloatingChromeHeight] tall, separated by [FloatingChromeGap] when both exist. Overlaid rather
             * than laid out in the page, which is what makes it float — the lists scroll underneath and
             * their colour comes through the material. `backdrop` captures the window underneath, and every
             * panel draws that same capture through the reference's material, so what shows through is a
             * blurred field rather than readable rows.
             */
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    // Inset once, here, rather than on each panel: both rows — the now-playing capsule and
                    // the tab bar — fill this same margin, so the two panels line up edge to edge.
                    .padding(horizontal = TabBarInset)
                    .padding(bottom = FloatingChromeBottomGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FloatingChromeGap),
            ) {
                // Top row: the now-playing capsule, present only while a track is loaded. With nothing
                // playing the row is not composed at all and the chrome is just the tab bar — the user
                // does not want the "未在播放" placeholder. The chrome column is bottom-anchored, so the
                // tab bar's screen position does not change when the row appears or goes away.
                //
                // Entrance/exit is the theme's small-motion budget: fade + a short rise/drop, with the
                // row's own height expanding/shrinking under it so the space above the tab row does not
                // pop. The chrome's *total* height is not animated against the content padding — the
                // padding follows the row count, not this animation.
                androidx.compose.animation.AnimatedVisibility(
                    visible = hasTrack,
                    // One kind of travel only: the row's own height expands under the fade. The slide
                    // added a second, second-order movement that lived inside the expanding clip (so
                    // it was largely invisible) and the exit was as slow as the entrance, unlike every
                    // other chrome overlay here (320/260, 200/160).
                    enter = fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)) +
                        expandVertically(
                            tween(Motion.SmallDuration, easing = Motion.EaseOut),
                            expandFrom = Alignment.Bottom,
                        ),
                    exit = fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)) +
                        shrinkVertically(
                            tween(Motion.SmallDuration, easing = Motion.EaseOut),
                            shrinkTowards = Alignment.Bottom,
                        ),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        FloatingMiniPlayer(
                            state = if (hasTrack) miniPlayerState else lastMiniPlayerState,
                            loader = container.artwork,
                            backdrop = bottomBackdrop,
                            onExpand = { showPlayer = true },
                            onTogglePlay = { container.player.togglePlayPause() },
                        )
                    }
                }

                // Bottom row: the tab bar fills the same width as the capsule above it (the column applies
                // the screen inset) and its four slots (主页 / 歌单广场 / 资料库 / 心动模式) divide that width
                // equally.
                FloatingTabBar(
                    selected = tab,
                    onSelect = { tab = if (showSettings) tab else it; showSettings = false },
                    onBeatMode = { openBeatMode() },
                    beatModeSignedIn = beatModeActions.signedIn,
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
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it } +
                    fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                exit = slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseOut)) { it } +
                    fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
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
                        CompositionLocalProvider(LocalBeatModeActions provides beatModeActions) {
                        CollectionScreen(
                            state = state,
                            loader = container.artwork,
                            onBack = appViewModel::closeCollection,
                            // Error state and the count row's refresh both replay the request that
                            // opened the page (playlist / liked / chart / album).
                            onRefresh = appViewModel::retryCollection,
                            onTrackClick = { track, index ->
                                play(track, state.tracks, state.id.takeIf { state.canBeatMode })
                            },
                            onPlayAll = {
                                state.tracks.firstOrNull()?.let {
                                    play(it, state.tracks, state.id.takeIf { state.canBeatMode })
                                }
                            },
                            onShufflePlay = {
                                // Shuffle is a player mode, not a one-off ordering: enabling it and
                                // then starting anywhere is what makes "next" stay random, whereas
                                // playing a shuffled copy would drift back to the list order.
                                container.player.toggleShuffle()
                                state.tracks.randomOrNull()?.let {
                                    play(it, state.tracks, state.id.takeIf { state.canBeatMode })
                                }
                            },
                            onShare = {
                                shareText(ShareText.playlist(state.title, state.id), "分享歌单")
                            },
                            nowPlayingId = nowPlaying.first,
                            isPlaying = nowPlaying.second,
                            onEditPlaylist = if (state.owned) {
                                {
                                    openPlaylistEdit(
                                        com.yunyin.music.core.Playlist(
                                            id = state.id,
                                            name = state.title,
                                            coverUrl = state.coverUrl,
                                            trackCount = state.tracks.size,
                                            description = state.description,
                                            creatorUserId = account?.userId ?: 0L,
                                        ),
                                    )
                                }
                            } else {
                                null
                            },
                            onDeletePlaylist = if (state.owned) {
                                {
                                    pendingDeletePlaylist = com.yunyin.music.core.Playlist(
                                        id = state.id,
                                        name = state.title,
                                        coverUrl = state.coverUrl,
                                        trackCount = state.tracks.size,
                                        description = state.description,
                                        creatorUserId = account?.userId ?: 0L,
                                    )
                                }
                            } else {
                                null
                            },
                            subscribed = state.subscribed,
                            onToggleSubscribe = if (state.subscribeAvailable) appViewModel::toggleCollectionSubscribe else null,
                            onPlayNext = { track ->
                                if (container.player.enqueueNext(listOf(track))) {
                                    Toast.makeText(context, "已设为下一首", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "设置下一首失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRemoveTrack = when {
                                state.kind == com.yunyin.music.ui.CollectionKind.Liked -> {
                                    { track -> pendingUnlikeTrack = track }
                                }
                                state.owned -> {
                                    { track -> pendingRemoveTrack = track }
                                }
                                else -> null
                            },
                        )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it },
                exit = slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseOut)) { it },
            ) {
                SettingsScreen(
                    onClearLyricsCache = {
                        container.lyrics.clearCache()
                        Toast.makeText(context, "歌词缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { showSettings = false },
                    onOpenProject = { openUrl(PROJECT_URL) },
                    // Read on each entry into Settings rather than held as state: the connection can
                    // change while the app runs (Lyricon installed or started later), and a value
                    // captured once would go stale exactly when the user opens this to check it.
                    lyriconConnected = container.lyricon.connected,
                    lyriconAvailable = container.lyricon.available,
                    onRetryLyricon = { container.lyricon.retry() },
                    lyriconEnabled = lyriconEnabled,
                    onLyriconEnabledChange = { enabled ->
                        // The bridge owns the actual connect/disconnect (and the persisted preference),
                        // so this only keeps the local state — which the switch renders — in step.
                        lyriconEnabled = enabled
                        container.lyricon.applyEnabled(enabled)
                    },
                    flymeSupported = container.tickerLyrics.isSupported(),
                    statusBarLyrics = statusBarLyricsEnabled,
                    onStatusBarLyricsChange = { enabled ->
                        statusBarLyricsEnabled = enabled
                        container.settings.statusBarLyrics = enabled
                        // Turning it off must remove the resident notification immediately rather
                        // than leaving it in the shade until the next track change.
                        if (!enabled) container.tickerLyrics.clear()
                    },
                    crossfadeSeconds = crossfadeSeconds,
                    seamlessTransition = seamlessTransition,
                    autoImmersiveLyrics = autoImmersiveLyrics,
                    onAutoImmersiveLyricsChange = { enabled ->
                        // Compose state + the store: the player reads the Compose state, so a toggle
                        // takes effect without a restart, and the store makes it survive one.
                        autoImmersiveLyrics = enabled
                        container.settings.autoImmersiveLyrics = enabled
                    },
                    nowPlayingCoverBlurBackground = nowPlayingCoverBlurBackground,
                    onNowPlayingCoverBlurBackgroundChange = { enabled ->
                        nowPlayingCoverBlurBackground = enabled
                        container.settings.nowPlayingCoverBlurBackground = enabled
                    },
                    nowPlayingAudioReactive = nowPlayingAudioReactive,
                    onNowPlayingAudioReactiveChange = { enabled ->
                        nowPlayingAudioReactive = enabled
                        container.settings.nowPlayingAudioReactive = enabled
                    },
                    nowPlayingDynamicBackground = nowPlayingDynamicBackground,
                    onNowPlayingDynamicBackgroundChange = { enabled ->
                        nowPlayingDynamicBackground = enabled
                        container.settings.nowPlayingDynamicBackground = enabled
                    },
                    sleepTimerLabel = when {
                        sleepTimerEndOfTrack -> "播完当前歌"
                        sleepTimerEnd != null -> {
                            val left = com.yunyin.music.playback.PlaybackSleepTimerRules
                                .remainingMinutes(System.currentTimeMillis(), sleepTimerEnd)
                            if (left != null) "还有约 $left 分钟" else "已开启"
                        }
                        else -> "关闭"
                    },
                    onShowSleepTimer = { showSleepTimer = true },
                    onSeamlessTransitionChange = { enabled ->
                        seamlessTransition = enabled
                        // Writing the preference is the whole action: the service watches this key and
                        // applies it to the running player. Reaching the player from here is not possible
                        // — `Player` and `MediaController` expose no skip-silence control — which is why
                        // the service owns that half.
                        container.settings.seamlessTransition = enabled
                    },
                    onTestFlymeLyric = {
                        container.tickerLyrics.runSelfTest()
                        Toast.makeText(context, "已发送测试歌词，请看状态栏是否滚动", Toast.LENGTH_LONG).show()
                    },
                    onCycleCrossfadeSeconds = {
                        val next = com.yunyin.music.playback.CrossfadeRules.DURATIONS_SECONDS.let { list ->
                            val i = list.indexOf(crossfadeSeconds).coerceAtLeast(0)
                            list[(i + 1) % list.size]
                        }
                        crossfadeSeconds = next
                        container.settings.crossfadeSeconds = next
                    },
                    // The crash file is app-private, so on an unrooted device it is unreachable without
                    // adb — and when the crash is on *launch*, adb is the only other route, which is no
                    // help to whoever is holding the phone. Copying it to the clipboard turns "it
                    // crashes" into a stack trace that can be pasted into a message.
                    hasCrashLog = remember(showSettings) { com.yunyin.music.CrashLogger.lastCrash(context) != null },
                    onCopyCrashLog = {
                        val report = com.yunyin.music.CrashLogger.lastCrash(context)
                        if (report == null) {
                            Toast.makeText(context, "没有崩溃日志", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("崩溃日志", report),
                            )
                            Toast.makeText(context, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    updateState = updateState,
                    updateConfigured = container.update.configured,
                    onCheckUpdate = {
                        // An explicit tap is a request to be told, so it bypasses the once-a-day throttle
                        // and clears a previous "skip this version" — and it reports the answer either way,
                        // including "already current", which a silent check could not.
                        container.update.check(force = true)
                    },
                    playerStyle = playerStyle,
                    onShowPlayerStyle = { showPlayerStyle = true },
                    lxSources = lxSources,
                    lxImporting = lxImporting,
                    lxImportMessage = lxImportMessage,
                    // Recomputed when Settings opens or after an import attempt, because the file is written by
                    // the failure path — a value captured once would never show the row it exists for.
                    lxErrorLogAvailable = remember(showSettings, lxImportMessage) {
                        container.lxSources.lastError() != null
                    },
                    onCopyLxErrorLog = {
                        val detail = container.lxSources.lastError()
                        if (detail == null) {
                            Toast.makeText(context, "没有音源错误日志", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("音源错误日志", detail),
                            )
                            Toast.makeText(context, "音源错误日志已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onImportLxSource = {
                        // `*/*` rather than a JS mime type: a source is distributed as a plain `.js` file and
                        // many providers report it as text/plain or octet-stream, so filtering by mime would
                        // hide the file the user came to pick. The content is validated after reading it.
                        lxScriptPicker.launch(arrayOf("*/*"))
                    },
                    onImportLxSourceFromUrl = {
                        // Opening the dialog is the tap the device crashed on. Nothing here can legitimately throw,
                        // but this is the UI boundary for a brand-new path: a failure becomes a message, not a crash.
                        runCatching {
                            lxUrlDraft = ""
                            lxImportMessage = null
                            lxUrlCloseRequest = false
            showLxUrlSheet = true
                        }.onFailure { t ->
                            lxImportMessage = "无法打开导入窗口：${(t.message ?: t.javaClass.simpleName).take(120)}"
                            runCatching { container.lxLog.record("打开从链接导入失败", t.stackTraceToString()) }
                        }
                    },
                    onRefreshLxSource = { id ->
                        scope.launch {
                            try {
                                val source = lxSources.firstOrNull { it.id == id } ?: return@launch
                                lxImporting = true
                                lxImportMessage = null
                                lxImportMessage = lxResultMessage(container.lxSources.refreshFromUrl(source))
                            } catch (t: Throwable) {
                                lxImportMessage = "导入时发生内部错误：${(t.message ?: t.javaClass.simpleName).take(120)}"
                                runCatching {
                                    container.lxLog.record("重新拉取音源异常", t.stackTraceToString())
                                }
                            } finally {
                                lxImporting = false
                                runCatching { lxSources = container.lxSources.list() }
                            }
                        }
                    },
                    onToggleLxSource = { id, enabled ->
                        scope.launch {
                            lxImportMessage = when (val result = container.lxSources.setEnabled(id, enabled)) {
                                is LxToggleResult.Collision ->
                                    "${result.key.wire} 已被「${result.otherName}」占用，同一音源只能启用一个脚本"
                                else -> null
                            }
                            lxSources = container.lxSources.list()
                        }
                    },
                    onDeleteLxSource = { id ->
                        scope.launch {
                            container.lxSources.delete(id)
                            lxSources = container.lxSources.list()
                        }
                    },
                )
            }

            // 从链接导入 dialog. A failure keeps it open with the URL and the specific reason; a success closes it
            // and the section underneath shows the usual "已导入「…」" line.
            if (showPlaylistEditor) {
                PlaylistEditorSheet(
                    title = if (playlistEditorTarget == null) "新建歌单" else "编辑歌单",
                    initialName = playlistEditorTarget?.name ?: "",
                    initialDescription = playlistEditorTarget?.description.orEmpty(),
                    busy = playlistEditorBusy,
                    message = playlistEditorMessage,
                    submitCloseRequest = playlistEditorCloseRequest,
                    onSubmit = { name, description -> submitPlaylist(name, description) },
                    onDismiss = {
                        if (!playlistEditorBusy) {
                            showPlaylistEditor = false
                            playlistEditorCloseRequest = false
                        }
                    },
                )
            }
            if (showPlaylistPicker) {
                PlaylistPickerSheet(
                    loading = playlistPickerLoading,
                    error = playlistPickerError,
                    playlists = playlistPickerPlaylists,
                    onPick = { target -> addTrackToPlaylist(target) },
                    onDismiss = { showPlaylistPicker = false },
                )
            }
            pendingDeletePlaylist?.let { target ->
                PlaylistConfirmSheet(
                    title = "删除歌单",
                    message = "确定删除「${target.name}」吗？",
                    confirmLabel = "删除",
                    busy = playlistWriteBusy,
                    closeRequest = deleteCloseRequest,
                    onConfirm = { deletePlaylistNow() },
                    onDismiss = {
                        if (!playlistWriteBusy) {
                            pendingDeletePlaylist = null
                            deleteCloseRequest = false
                        }
                    },
                )
            }
            pendingRemoveTrack?.let { track ->
                PlaylistConfirmSheet(
                    title = "移除歌曲",
                    message = "从歌单中移除「${track.name}」？",
                    confirmLabel = "移除",
                    busy = playlistWriteBusy,
                    closeRequest = removeCloseRequest,
                    onConfirm = { removeTrackNow() },
                    onDismiss = {
                        if (!playlistWriteBusy) {
                            pendingRemoveTrack = null
                            removeCloseRequest = false
                        }
                    },
                )
            }
            pendingUnlikeTrack?.let { track ->
                PlaylistConfirmSheet(
                    title = "取消喜欢",
                    message = "取消喜欢「${track.name}」？",
                    confirmLabel = "取消喜欢",
                    busy = false,
                    closeRequest = unlikeCloseRequest,
                    onConfirm = { removeLikedNow() },
                    onDismiss = {
                        pendingUnlikeTrack = null
                        unlikeCloseRequest = false
                    },
                )
            }
            if (showSleepTimer) {
                SleepTimerSheet(
                    activeMinutesLeft = com.yunyin.music.playback.PlaybackSleepTimerRules
                        .remainingMinutes(System.currentTimeMillis(), sleepTimerEnd),
                    endOfTrackArmed = sleepTimerEndOfTrack,
                    onPick = { option -> pickSleepTimer(option) },
                    onDismiss = { showSleepTimer = false },
                )
            }
            if (showLxUrlSheet) {
                LxSourceUrlSheet(
                    url = lxUrlDraft,
                    importing = lxImporting,
                    message = lxImportMessage,
                    onUrlChange = { lxUrlDraft = it },
                    onImport = { importLxFromUrl() },
                    closeRequest = lxUrlCloseRequest,
                    onDismiss = {
                        showLxUrlSheet = false
                        lxUrlCloseRequest = false
                        lxUrlDraft = ""
                        lxImportMessage = null
                    },
                )
            }

            // The profile editor.
            //
            // Reachable for a guest too: the editor writes local presentation (nickname, avatar,
            // background, signature), none of which needs an account. The earlier signed-in-only gate was
            // written when it only changed cloud identity, and is stale now.
            if (showProfileEdit) {
                ProfileEditSheet(
                    nickname = account?.nickname.orEmpty(),
                    signature = signature,
                    useNeteaseAvatar = useNeteaseAvatar,
                    useNeteaseName = useNeteaseName,
                    hasCustomAvatar = container.profile.avatarFile() != null,
                    hasCustomBackground = container.profile.backgroundFile() != null,
                    onNicknameChange = { value ->
                        customNickname = value
                        container.settings.customNickname = value
                    },
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
                    onUseNeteaseNameChange = { enabled ->
                        useNeteaseName = enabled
                        container.settings.useNeteaseName = enabled
                        if (!enabled && container.settings.customNickname.isBlank()) {
                            // Turning "follow" off with no local name would leave the header on a
                            // fallback string; seed the field with the account's own so the user edits
                            // from something.
                            customNickname = account?.nickname.orEmpty()
                            container.settings.customNickname = customNickname
                        }
                    },
                    onDismiss = { showProfileEdit = false },
                )
            }

            AnimatedVisibility(
                visible = showLogin,
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it },
                exit = slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseOut)) { it },
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
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it } + fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                exit = slideOutVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it } + fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
            ) {
                // Collected here, inside the player's own branch, so the 4x/second position tick
                // invalidates only the player screen and never the browsing tabs behind it.
                val playback by container.player.state.collectAsState()
                // One shared controller for the player chrome and its action sheet. The state map lives in the
                // container, so a download survives navigating away from the player (and the sheet reflects it).
                val downloadStates by container.downloads.states.collectAsState()
                CompositionLocalProvider(
                    LocalBeatModeActions provides beatModeActions,
                    LocalDownloadController provides DownloadController(
                        states = downloadStates,
                        onDownload = { track -> container.downloads.download(track) },
                        onCancel = { trackId -> container.downloads.cancel(trackId) },
                    ),
                    com.yunyin.music.ui.playlist.LocalPlaylistActions provides
                        com.yunyin.music.ui.playlist.PlaylistActions(
                            onAddToPlaylist = { track -> openPlaylistPicker(track) },
                        ),
                    com.yunyin.music.ui.comments.LocalCommentActions provides
                        com.yunyin.music.ui.comments.CommentActions(
                            onOpen = { playback.current?.let { track -> appViewModel.openComments(track.id, track.name) } },
                        ),
                ) {
                    PlayerScreen(
                    state = playback,
                    cover = playerViewModel.coverBitmap,
                    palette = playerViewModel.palette,
                    lyrics = playerViewModel.lyrics,
                    lyricsLoading = playerViewModel.lyricsLoading,
                    lyricsEmptyText = playerViewModel.lyricsEmptyMessage,
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
                    onTogglePlay = { container.player.togglePlayPause() },
                    onNext = { container.player.next() },
                    onPrevious = { container.player.previous() },
                    onSeek = { positionMs -> container.player.seekTo(positionMs) },
                    onCycleRepeat = { container.player.cycleRepeat() },
                    onToggleShuffle = { container.player.toggleShuffle() },
                    onShowQueue = { showQueue = true },
                    // Fallback entry for 私人 FM beside the Home row: discoverable from the player's
                    // ⋯ sheet even if the Home shelf is scrolled away or the feed is degraded.
                    onStartFm = { appViewModel.startFm() },
                    onShowSettings = { showSettings = true; showPlayer = false },
                    onArtistClick = { name ->
                        // The artist page sits above the player: closing it returns to where the tap
                        // happened, which is the least surprising layering for a page reached mid-play.
                        appViewModel.openArtistByName(name)
                    },
                    lyricOffsetMs = playerViewModel.lyricOffsetMs,
                    onLyricOffsetChange = playerViewModel::setLyricOffset,
                    autoImmersiveLyrics = autoImmersiveLyrics,
                    nowPlayingCoverBlurBackground = nowPlayingCoverBlurBackground,
                    nowPlayingAudioReactive = nowPlayingAudioReactive,
                    nowPlayingDynamicBackground = nowPlayingDynamicBackground,
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
                    playerStyle = playerStyle,
                )
                }
            }

            // Transient messages from the player (a refused like, a failed share).
            LaunchedEffect(playerViewModel.notice) {
                val message = playerViewModel.notice ?: return@LaunchedEffect
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                playerViewModel.clearNotice()
            }

            // Download results are a Toast, matching the app's existing transient-message language. The manager emits
            // one per event ("正在下载…", "已保存到 …", "下载失败：…", "已取消下载") so a tap can never be a silent
            // no-op — the sheet closes on tap, so a changed row label alone was not visible enough.
            LaunchedEffect(Unit) {
                container.downloads.notices.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            // The artist page, layered over the player.
            //
            // The state is kept alive through the exit animation for the same reason as the collection
            // page: closing nulls it immediately, and an exit transition running on nothing reads as the
            // page vanishing.
            var lastArtist by remember { mutableStateOf<ArtistUiState?>(null) }
            SideEffect { artistState?.let { lastArtist = it } }

            AnimatedVisibility(
                visible = artistState != null,
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it } +
                    fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                exit = slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseOut)) { it } +
                    fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
            ) {
                val artist = lastArtist
                if (artist != null) {
                    val nowPlaying by remember {
                        playerState
                            .map { it.current?.id to it.isPlaying }
                            .distinctUntilChanged()
                    }.collectAsState(initial = null to false)

                    Surface(Modifier.fillMaxSize(), color = AppTheme.palette.background) {
                        ArtistScreen(
                            state = artist,
                            loader = container.artwork,
                            onBack = appViewModel::closeArtist,
                            onTrackClick = { track, index -> play(track, artist.hotSongs.ifEmpty { artist.allSongs }) },
                            onAllTrackClick = { track, index -> play(track, artist.allSongs) },
                            onPlayAll = {
                                val list = artist.hotSongs.ifEmpty { artist.allSongs }
                                list.firstOrNull()?.let { play(it, list) }
                            },
                            onShufflePlay = {
                                val list = artist.hotSongs.ifEmpty { artist.allSongs }
                                container.player.toggleShuffle()
                                list.randomOrNull()?.let { play(it, list) }
                            },
                            followed = artist.followed,
                            onToggleFollow = if (artist.followAvailable) appViewModel::toggleArtistFollow else null,
                            onLoadMoreSongs = { appViewModel.loadMoreArtistSongs() },
                            onLoadMoreAlbums = { appViewModel.loadMoreArtistAlbums() },
                            onAlbumClick = { album ->
                                // The artist page is layered ABOVE the collection page, so opening the
                                // album without closing the artist left it rendering underneath an
                                // opaque page — the tap looked like a no-op. id is the album's, not the
                                // artist's, so openAlbum lands on the right detail page.
                                appViewModel.closeArtist()
                                appViewModel.openAlbum(album.id, album.name, album.coverUrl)
                            },
                            nowPlayingId = nowPlaying.first,
                            isPlaying = nowPlaying.second,
                        )
                    }
                }
            }

            // The comments page, layered over the player like the artist page and keeping its
            // state through the exit animation so the outgoing page has something to slide.
            var lastComments by remember { mutableStateOf<com.yunyin.music.ui.CommentsUiState?>(null) }
            SideEffect { commentsState.takeIf { it.visible }?.let { lastComments = it } }

            AnimatedVisibility(
                visible = commentsState.visible,
                enter = slideInVertically(tween(Motion.SheetEnterDuration, easing = Motion.EaseOut)) { it } +
                    fadeIn(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
                exit = slideOutVertically(tween(Motion.SheetExitDuration, easing = Motion.EaseOut)) { it } +
                    fadeOut(tween(Motion.SmallDuration, easing = Motion.EaseOut)),
            ) {
                val screenState = if (commentsState.visible) commentsState else lastComments
                if (screenState != null) {
                    Surface(Modifier.fillMaxSize(), color = AppTheme.palette.background) {
                        com.yunyin.music.ui.comments.CommentsScreen(
                            state = screenState,
                            loader = container.artwork,
                            onBack = appViewModel::closeComments,
                            onSelectTab = appViewModel::selectCommentsTab,
                            onLoadMore = appViewModel::loadMoreComments,
                            onRetry = appViewModel::retryComments,
                        )
                    }
                }
            }

            // Queue sheet. It animates itself inside the Dialog; an outer AnimatedVisibility would
            // be a second, redundant transition (same reason as the profile editor).
            if (showQueue) {
                val playback by container.player.state.collectAsState()
                QueueSheet(
                    queue = playback.queue,
                    currentIndex = playback.index,
                    loader = container.artwork,
                    onSelect = { index -> container.player.seekToIndex(index) },
                    onDismiss = { showQueue = false },
                )
            }

            if (showPlayerStyle) {
                PlayerStyleSheet(
                    current = playerStyle,
                    onSelect = { picked ->
                        playerStyle = picked
                        container.settings.playerStyle = picked.level
                        showPlayerStyle = false
                    },
                    onDismiss = { showPlayerStyle = false },
                )
            }

            // An automatic update check, once a day at most (see UpdateManager.autoCheckIfDue).
            //
            // On `LaunchedEffect(Unit)` rather than tied to the splash: the check must not be able to hold
            // up the launch screen, and it must not delay anything a user can see. Its failures are silent
            // by design — on a network that filters GitHub, an updater that reported an error every launch
            // would be worse than one that quietly tries again tomorrow.
            LaunchedEffect(Unit) {
                container.update.autoCheckIfDue()
            }

            // What a *manual* check has to say ("已是最新版本", or why it failed). Automatic checks stay
            // quiet, so this only ever fires for a tap the user made themselves.
            LaunchedEffect(Unit) {
                container.update.notice.collect { message ->
                    if (message != null) {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        container.update.clearNotice()
                    }
                }
            }

            // The update sheet. Holds the last real update through its own dismissal so the exit
            // animation has something to run on — the same pattern the artist and collection pages use.
            var lastUpdate by remember { mutableStateOf<com.yunyin.music.data.update.AvailableUpdate?>(null) }
            SideEffect {
                when (val s = updateState) {
                    is com.yunyin.music.data.update.UpdateUiState.Available -> lastUpdate = s.update
                    is com.yunyin.music.data.update.UpdateUiState.AvailableSkipped -> lastUpdate = s.update
                    is com.yunyin.music.data.update.UpdateUiState.Downloading -> Unit
                    else -> Unit
                }
            }
            // Shown for anything except the idle state, so a failed download inside the sheet stays
            // visible (with its own message and the "浏览器打开" fallback) rather than vanishing silently.
            if (lastUpdate != null) {
                val shown = lastUpdate
                if (shown != null) {
                    UpdateSheet(
                        state = updateState,
                        onInstall = { container.update.downloadAndInstall(it) },
                        onOpenPage = { update ->
                            // Dismiss first: leaving the sheet up under the browser looks like the app is
                            // offering the same version twice.
                            container.update.dismiss()
                            openUrl(update.pageUrl)
                        },
                        onSkip = { container.update.skip(it) },
                        onDismiss = {
                            container.update.dismiss()
                            // Cleared only after the sheet's exit has finished.
                            lastUpdate = null
                        },
                    )
                }
            }

        }

        // Android back gesture / button.
        //
        // Handlers are declared least-important-first: with several enabled, the one
        // composed last wins, so this unwinds overlays in the reverse order they opened
        // (queue, then login/settings, then the player, then a detail page, then the tab).
        BackHandler(enabled = tab != PlayerTab.Home) { tab = PlayerTab.Home }
        BackHandler(enabled = collectionState != null) { appViewModel.closeCollection() }
        BackHandler(enabled = artistState != null) { appViewModel.closeArtist() }
        BackHandler(enabled = showPlayer) { showPlayer = false }
        BackHandler(enabled = commentsState.visible) { appViewModel.closeComments() }
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = showLogin) { showLogin = false }

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

    /**
     * Opens [url] in whatever handles web links.
     *
     * Guarded because there may be no browser (or no handler at all) on the device: an
     * `ActivityNotFoundException` here would crash the app from a settings tap, which is a poor trade
     * for a link that is only a convenience.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
    }
}

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
