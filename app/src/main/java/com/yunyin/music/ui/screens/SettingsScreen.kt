// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.yunyin.music.data.lxsource.LxImportedSource
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.update.UpdateUiState
import com.yunyin.music.playback.CrossfadeRules
import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.background.BackgroundStyle
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppPalette
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleColors
import com.yunyin.music.ui.theme.SFPro
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Where the project lives. Defined once: the About row displays it and the caller opens it, so a
 * second literal would be one place for the two to drift apart.
 */
internal const val PROJECT_URL = "https://github.com/Fu-craft/YunYin"

/**
 * Settings.
 *
 * Only user-facing choices appear here: maintenance actions and status. Audio quality is deliberately
 * **not** here — it is something people change while listening, so it lives on the player itself as a
 * tappable chip. Server/network configuration is also absent: the app ships with a working service
 * endpoint, and exposing addresses, proxies or IP overrides asks the user to reason about
 * infrastructure they have no reason to know about.
 *
 * ## Presentation
 *
 * The page is built from Miuix preferences rather than hand-rolled rows: every group is a miuix
 * [Card] under a [SmallTitle], and every row is an [ArrowPreference] (a tap that goes somewhere), a
 * [SwitchPreference] (a value with two states) or a [SliderPreference] (a value on a scale). That is
 * what gives the screen its familiar list rhythm — the tap target fills the row, the divider is
 * inset to the text, and the press feedback is the platform's rather than ours.
 *
 * The whole page is wrapped in [MiuixTheme], whose color scheme is *derived from* `AppTheme.palette`
 * rather than replacing it: the app's accent, label scale and grouped backgrounds are mapped onto the
 * miuix roles (see [yunyinMiuixColors]). The palette stays the single source of truth, so an artwork
 * accent override still reaches these controls, and the rows sit on the same background as the rest of
 * the app.
 */
@Composable
fun SettingsScreen(
    onClearLyricsCache: () -> Unit,
    onBack: () -> Unit,
    /** Opens the download centre. */
    onOpenDownloads: () -> Unit = {},
    /** Wi-Fi-only download switch; default on in the store. */
    downloadWifiOnly: Boolean = true,
    onDownloadWifiOnlyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Opens the project page in a browser; the URL is [PROJECT_URL]. */
    onOpenProject: () -> Unit = {},
    /**
     * State of the 词幕 (Lyricon) bridge, for display.
     *
     * Surfaced here because the integration is otherwise invisible: the status bar is rendered by
     * another app, so without a line stating whether the bridge connected there is no way to tell
     * "Lyricon is not installed" from "it is installed and not working".
     */
    lyriconConnected: Boolean = false,
    lyriconAvailable: Boolean = false,
    onRetryLyricon: () -> Unit = {},
    /**
     * Whether the app publishes to 词幕 at all.
     *
     * The integration only makes sense on a device that has 词幕 installed, and even there someone may
     * not want their lyrics mirrored outside the app — so it can be switched off, which drops the
     * binder connection rather than merely hiding the status line.
     */
    lyriconEnabled: Boolean = true,
    onLyriconEnabledChange: (Boolean) -> Unit = {},
    /**
     * Flyme 状态栏歌词 availability and preference.
     *
     * [flymeSupported] is false on any ROM that did not port the feature — which is most of them — so
     * the row explains that rather than presenting a switch that does nothing.
     */
    flymeSupported: Boolean = false,
    statusBarLyrics: Boolean = true,
    onStatusBarLyricsChange: (Boolean) -> Unit = {},
    /** Floating desktop lyrics; the host handles the overlay permission before starting the service. */
    desktopLyricsEnabled: Boolean = false,
    onDesktopLyricsEnabledChange: (Boolean) -> Unit = {},
    overlayPermissionGranted: Boolean = false,
    onRequestOverlayPermission: () -> Unit = {},
    /** Whether tracks run into each other without the dead air that makes them sound like files. */
    seamlessTransition: Boolean = true,
    onSeamlessTransitionChange: (Boolean) -> Unit = {},
    /**
     * Overlap length in seconds for the dual-player crossfade.
     *
     * Chosen on a slider rather than cycled by tapping: a duration is a value on a scale, so it reads
     * better as a track under the finger than as four taps to come back to where it started.
     */
    crossfadeSeconds: Int = 6,
    /** Sets the overlap directly; the host persists it and the service picks it up. */
    onCrossfadeSecondsChange: (Int) -> Unit = {},
    /**
     * Legacy cycle action, kept so an existing host call site keeps compiling.
     *
     * The row that used to cycle through [CrossfadeRules.DURATIONS_SECONDS] is now a
     * [SliderPreference]; nothing in this screen calls this any more.
     */
    onCycleCrossfadeSeconds: () -> Unit = {},
    /** CurrentMusic 账号区块；由宿主注入，避免这里依赖 SettingsStore。 */
    currentMusicSection: @Composable () -> Unit = {},
    /** Playlist showcase for the home shelf: off / ribbon / shelf. */
    playlistShowcaseLabel: String = "关闭",
    onCyclePlaylistShowcase: () -> Unit = {},
    /** Settings → 播放 → 测试状态栏歌词 (cycles four recipes). */
    onTestFlymeLyric: () -> Unit = {},
    /** Whether the lyrics page auto-hides the chrome after the idle delay (default off). */
    autoImmersiveLyrics: Boolean = false,
    onAutoImmersiveLyricsChange: (Boolean) -> Unit = {},
    /**
     * The three now-playing background switches (ported from NeriPlayer's 正在播放 section).
     * Defaults are NeriPlayer's: dynamic on, audio reactive on, blurred cover off.
     */
    nowPlayingCoverBlurBackground: Boolean = false,
    onNowPlayingCoverBlurBackgroundChange: (Boolean) -> Unit = {},
    nowPlayingAudioReactive: Boolean = true,
    onNowPlayingAudioReactiveChange: (Boolean) -> Unit = {},
    nowPlayingDynamicBackground: Boolean = true,
    onNowPlayingDynamicBackgroundChange: (Boolean) -> Unit = {},
    /** 云音 / 封面流动 / 色阶流动; changing it applies immediately through the host state. */
    nowPlayingBackgroundStyle: BackgroundStyle = BackgroundStyle.default,
    onShowBackgroundStyle: () -> Unit = {},
    /** Hidden entirely when no operator endpoint is configured. */
    animatedCoverAvailable: Boolean = false,
    animatedCoverEnabled: Boolean = false,
    onAnimatedCoverChange: (Boolean) -> Unit = {},
    /** Runs one canned lookup so the device can be told apart from the endpoint. */
    onTestAnimatedCover: (() -> Unit)? = null,
    /** Remaining sleep-timer label ("关闭" / "还有约 12 分钟" / "播完当前歌"). */
    sleepTimerLabel: String = "关闭",
    onShowSleepTimer: () -> Unit = {},
    /**
     * Whether a crash has been recorded since the last one was cleared.
     *
     * Drives the visibility of the "复制上次崩溃日志" row: the app writes every uncaught exception to
     * `filesDir/crash-last.txt`, but that file is unreachable without adb on an unrooted device — and a
     * launch crash makes adb the *only* other route, which is useless to the person holding the phone.
     */
    hasCrashLog: Boolean = false,
    /** Copies the recorded crash text to the clipboard so it can be pasted into a message. */
    onCopyCrashLog: () -> Unit = {},
    /**
     * The update check's state, for the "检查更新" row.
     *
     * A whole state rather than a boolean because the row has to say four different things — checking,
     * current, a version is available, and a failed check — and an updater that cannot distinguish "no
     * update" from "could not ask" is one that lies to the user about being current.
     */
    updateState: UpdateUiState = UpdateUiState.Idle,
    /** Hides the row entirely in a build with no update repository configured. */
    updateConfigured: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    /** The player presentation currently chosen, shown on the 播放器界面 row. */
    playerStyle: PlayerStyle = PlayerStyle.Classic,
    /** Opens the picker; the choosing itself lives in the sheet so this screen stays a list. */
    onShowPlayerStyle: () -> Unit = {},
    onShowCoverMotionTuner: () -> Unit = {},
    /** Imported LX (洛雪) custom sources; empty until one is imported. */
    lxSources: List<LxImportedSource> = emptyList(),
    /** True while a picked script is being run and validated. */
    lxImporting: Boolean = false,
    /** The result of the last import or toggle, shown under the import row. */
    lxImportMessage: String? = null,
    /**
     * Whether a custom-source failure detail has been recorded.
     *
     * The short reason is in [lxImportMessage]; the JS stack that explains *why* is in a file, and on an
     * unrooted device that file is unreachable without adb — the same problem the crash-log row solves. The
     * row therefore appears only when there is something to copy.
     */
    lxErrorLogAvailable: Boolean = false,
    /** Copies the recorded custom-source failure detail to the clipboard. */
    onCopyLxErrorLog: () -> Unit = {},
    /** Opens the document picker; the picking and importing live in the host so this screen stays a list. */
    onImportLxSource: () -> Unit = {},
    /** Opens the URL dialog; the fetching/validation happens in the host. */
    onImportLxSourceFromUrl: () -> Unit = {},
    /** Re-downloads a URL-sourced script and replaces it. */
    onRefreshLxSource: (String) -> Unit = {},
    onToggleLxSource: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteLxSource: (String) -> Unit = {},
) {
    val palette = AppTheme.palette
    // The frosted navigation strip: the recorded page layer the bar samples, with the bar itself
    // composed outside it (after the scroll column closes, below).
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val scrollState = rememberScrollState()

    MiuixTheme(colors = yunyinMiuixColors(palette)) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // The screen is a full-window overlay and is not wrapped in a Surface by its host, so it
                // has to paint its own page background; the recorded layer above paints the same colour
                // *into the blur field* (that fill is what the top strip's blur samples).
                .background(palette.background),
        ) {
            Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(top = glassTopBarTopPadding()),
                ) {
                    // Large in-content title: it scrolls under the frosted strip; the bar stays title-less.
                    Text(
                        text = "设置",
                        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                    )

                    SettingsGroup(title = "播放") {
                        SwitchPreference(
                            checked = seamlessTransition,
                            onCheckedChange = onSeamlessTransitionChange,
                            title = "无缝衔接",
                            summary = "切歌时让两首重叠淡入淡出",
                        )
                        GroupDivider()
                        SliderPreference(
                            value = crossfadeSeconds.toFloat(),
                            onValueChange = { raw ->
                                // The slider is stepped to the four supported durations, so the rounded
                                // value is always one of them; a repeated value must not write the store
                                // on every frame of a drag.
                                val seconds = raw.roundToInt()
                                if (seconds != crossfadeSeconds) onCrossfadeSecondsChange(seconds)
                            },
                            title = "交叉淡化时长",
                            summary = "切歌重叠的时长",
                            valueText = "$crossfadeSeconds 秒",
                            valueRange = CrossfadeRules.DURATIONS_SECONDS.first().toFloat()..
                                CrossfadeRules.DURATIONS_SECONDS.last().toFloat(),
                            steps = (CrossfadeRules.DURATIONS_SECONDS.size - 2).coerceAtLeast(0),
                        )
                        GroupDivider()
                        ArrowPreference(
                            title = "定时关闭",
                            summary = "到点后自动停止播放",
                            endActions = { PreferenceValue(sleepTimerLabel) },
                            onClick = onShowSleepTimer,
                        )
                        GroupDivider()
                        ArrowPreference(
                            title = "播放器界面",
                            summary = "播放页展示封面或唱片",
                            endActions = { PreferenceValue(playerStyle.label) },
                            onClick = onShowPlayerStyle,
                        )
                        GroupDivider()
                        ArrowPreference(
                            title = "歌单展示",
                            summary = "首页歌单用立体缎带或CD唱片架",
                            endActions = { PreferenceValue(playlistShowcaseLabel) },
                            onClick = onCyclePlaylistShowcase,
                        )
                        GroupDivider()
                        ArrowPreference(
                            title = "封面动效手感",
                            summary = "调整封面缩小与回弹的软硬",
                            endActions = { PreferenceValue("暂停与恢复的弹簧") },
                            onClick = onShowCoverMotionTuner,
                        )
                        GroupDivider()
                        SwitchPreference(
                            checked = autoImmersiveLyrics,
                            onCheckedChange = onAutoImmersiveLyricsChange,
                            title = "自动进入沉浸歌词",
                            summary = "歌词页闲置几秒后隐藏播放控件",
                        )
                    }

                    // The 一起听 block is injected by the host and brings its own presentation, so it
                    // sits under a section title rather than inside a card this screen owns wrapping it.
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        SmallTitle("一起听")
                        currentMusicSection()
                    }

                    SettingsGroup(title = "正在播放外观") {
                        SwitchPreference(
                            checked = nowPlayingCoverBlurBackground,
                            onCheckedChange = onNowPlayingCoverBlurBackgroundChange,
                            title = "模糊封面背景",
                            summary = "用当前封面作为播放页背景",
                        )
                        GroupDivider()
                        SwitchPreference(
                            checked = nowPlayingAudioReactive,
                            onCheckedChange = onNowPlayingAudioReactiveChange,
                            title = "音频律动",
                            summary = "背景随音频节奏变化",
                        )
                        GroupDivider()
                        SwitchPreference(
                            checked = nowPlayingDynamicBackground,
                            onCheckedChange = onNowPlayingDynamicBackgroundChange,
                            title = "动态背景",
                            summary = "显示随时间变化的动态背景",
                        )
                        if (animatedCoverAvailable) {
                            GroupDivider()
                            SwitchPreference(
                                checked = animatedCoverEnabled,
                                onCheckedChange = onAnimatedCoverChange,
                                title = "动态封面",
                                // Naming the host makes it obvious which endpoint this build resolves
                                // against, so "the toggle is on but nothing moves" can be told apart
                                // from "this build has no artwork endpoint at all".
                                summary = "为当前歌曲显示服务端动态封面 · " +
                                    com.yunyin.music.BuildConfig.ANIMATED_ARTWORK_BASE_URL
                                        .removePrefix("https://")
                                        .removePrefix("http://")
                                        .trimEnd('/') +
                                    " · v${com.yunyin.music.BuildConfig.VERSION_NAME}",
                            )
                            if (animatedCoverEnabled) {
                                // What the last track lookup actually did: no request, 404, 5xx, a hit
                                // whose stream the player rejected, or a hit that is now playing.
                                val artworkStatus by com.yunyin.music.data.AnimatedArtworkDiagnostics
                                    .status.collectAsState()
                                GroupDivider()
                                // A sentence, not a value: it goes in the summary so it can wrap
                                // instead of being ellipsised in the narrow end slot.
                                InfoPreference(title = "接口状态", summary = artworkStatus)
                                if (onTestAnimatedCover != null) {
                                    GroupDivider()
                                    ArrowPreference(
                                        title = "测试接口",
                                        summary = "用 Taylor Swift《Lover》查一次，验证网络与接口",
                                        onClick = onTestAnimatedCover,
                                    )
                                }
                            }
                        }
                        GroupDivider()
                        ArrowPreference(
                            title = "背景样式",
                            summary = "选择正在播放页的动态背景",
                            endActions = { PreferenceValue(nowPlayingBackgroundStyle.label) },
                            onClick = onShowBackgroundStyle,
                        )
                    }

                    SettingsGroup(
                        title = "状态栏歌词",
                        footnote = "词幕需已安装并在系统中启用；Flyme 歌词只在对应系统上可用。",
                    ) {
                        SwitchPreference(
                            checked = lyriconEnabled,
                            onCheckedChange = onLyriconEnabledChange,
                            title = "推送到词幕",
                            summary = "由「词幕」显示歌词",
                        )
                        GroupDivider()
                        LyriconPreference(
                            enabled = lyriconEnabled,
                            connected = lyriconConnected,
                            available = lyriconAvailable,
                            onRetry = onRetryLyricon,
                        )
                        GroupDivider()
                        // Only interactive on a ROM that ported the feature: the switch stays visible but
                        // disabled there, because a row that silently loses its control reads as a bug.
                        SwitchPreference(
                            checked = statusBarLyrics,
                            onCheckedChange = onStatusBarLyricsChange,
                            title = "Flyme 状态栏歌词",
                            summary = if (flymeSupported) {
                                "由系统通知栏显示，需保持常驻通知"
                            } else {
                                "当前系统未内置该功能"
                            },
                            enabled = flymeSupported,
                        )
                        GroupDivider()
                        SwitchPreference(
                            checked = desktopLyricsEnabled,
                            onCheckedChange = onDesktopLyricsEnabledChange,
                            title = "桌面歌词",
                            summary = "在其他应用上方显示当前歌词",
                        )
                        if (desktopLyricsEnabled && !overlayPermissionGranted) {
                            GroupDivider()
                            ArrowPreference(
                                title = "悬浮窗权限",
                                summary = "允许云音显示在其他应用上方",
                                endActions = {
                                    PreferenceValue(
                                        "未授权",
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                },
                                onClick = onRequestOverlayPermission,
                            )
                        }
                        GroupDivider()
                        ActionPreference(
                            title = "测试状态栏歌词",
                            summary = "发送一条测试歌词到状态栏",
                            onClick = onTestFlymeLyric,
                        )
                    }

                    LxSourcesGroup(
                        sources = lxSources,
                        importing = lxImporting,
                        message = lxImportMessage,
                        errorLogAvailable = lxErrorLogAvailable,
                        onCopyErrorLog = onCopyLxErrorLog,
                        onImport = onImportLxSource,
                        onImportFromUrl = onImportLxSourceFromUrl,
                        onRefresh = onRefreshLxSource,
                        onToggle = onToggleLxSource,
                        onDelete = onDeleteLxSource,
                    )

                    SettingsGroup(
                        title = "下载与存储",
                        footnote = "清除歌词缓存不会删除本地音乐或已下载的歌曲。",
                    ) {
                        ArrowPreference(
                            title = "下载中心",
                            summary = "查看进行中、已完成与失败的下载",
                            onClick = onOpenDownloads,
                        )
                        GroupDivider()
                        SwitchPreference(
                            checked = downloadWifiOnly,
                            onCheckedChange = onDownloadWifiOnlyChange,
                            title = "仅 Wi-Fi 下载",
                            summary = "避免用移动数据开始下载",
                        )
                        GroupDivider()
                        ActionPreference(
                            title = "清除歌词缓存",
                            summary = "删除已下载的歌词文件",
                            onClick = onClearLyricsCache,
                        )
                    }

                    SettingsGroup(title = "关于") {
                        InfoPreference(
                            title = "版本",
                            value = com.yunyin.music.BuildConfig.VERSION_NAME,
                        )
                        if (updateConfigured) {
                            GroupDivider()
                            // A failed check stays tappable so the user can retry; every other state
                            // is also a tap because asking again is harmless.
                            ArrowPreference(
                                title = "检查更新",
                                endActions = { PreferenceValue(updateRowValue(updateState)) },
                                onClick = onCheckUpdate,
                            )
                        }
                        GroupDivider()
                        InfoPreference(title = "歌词来源", value = "AMLL 逐字歌词")
                        GroupDivider()
                        LicensePreference(
                            name = "云音（本项目）",
                            license = "AGPL-3.0-only",
                            url = "$PROJECT_URL/blob/master/LICENSE",
                        )
                        GroupDivider()
                        ArrowPreference(
                            title = "项目主页",
                            summary = "在浏览器中打开 GitHub 仓库",
                            endActions = { PreferenceValue("GitHub") },
                            onClick = onOpenProject,
                        )
                        if (hasCrashLog) {
                            GroupDivider()
                            ActionPreference(
                                title = "复制上次崩溃日志",
                                summary = "把崩溃时的调用栈放进剪贴板",
                                onClick = onCopyCrashLog,
                            )
                        }
                    }

                    SettingsGroup(
                        title = "开源组件",
                        footnote = "各组件许可证与上游地址；完整声明见仓库 NOTICE。",
                    ) {
                        LicensePreference(
                            name = "AMLL / Mesh Gradient",
                            license = "AGPL-3.0-only",
                            url = "https://github.com/amll-dev/applemusic-like-lyrics",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "QPlayer",
                            license = "Apache-2.0",
                            url = "https://github.com/TIMER-err/QPlayer",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "NeriPlayer",
                            license = "GPL-3.0",
                            url = "https://github.com/cwuom/NeriPlayer",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "accompanist lyrics-core",
                            license = "Apache-2.0",
                            url = "https://github.com/6xingyv/Accompanist-Lyrics",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "accompanist lyrics-ui（vendored）",
                            license = "Apache-2.0",
                            url = "https://github.com/6xingyv/Accompanist-Lyrics",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "io.github.kyant0:backdrop",
                            license = "Apache-2.0",
                            url = "https://github.com/Kyant0/AndroidLiquidGlass",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "androidx.media3",
                            license = "Apache-2.0",
                            url = "https://github.com/androidx/media",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "OkHttp",
                            license = "Apache-2.0",
                            url = "https://github.com/square/okhttp",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "Coil",
                            license = "Apache-2.0",
                            url = "https://github.com/coil-kt/coil",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "QuickJS / quickjs-kt",
                            license = "Apache-2.0",
                            url = "https://github.com/dokar3/quickjs-kt",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "Material Symbols",
                            license = "Apache-2.0",
                            url = "https://github.com/google/material-design-icons",
                        )
                        GroupDivider()
                        LicensePreference(
                            name = "Lucide（FolderMusic 图标）",
                            license = "ISC",
                            url = "https://github.com/lucide-icons/lucide",
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            GlassTopBar(
                backdrop = topBarBackdrop,
                modifier = Modifier.align(Alignment.TopCenter),
                tint = palette.background,
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 44dp for a comfortable touch target; the glyph stays 22dp so the header reads the same.
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            SfIcons.ChevronLeft,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps the app's [AppPalette] onto the miuix color scheme.
 *
 * The palette is the single source of truth for colour in this app (it carries the artwork accent
 * override), so the miuix roles are *derived* from it rather than restated: the accent becomes
 * `primary` — which is what a miuix switch track, slider and button fill use — the label scale
 * becomes the `on*` roles, and the grouped backgrounds become `surfaceContainer`, the colour a
 * [Card] paints. Whatever the palette does not distinguish (error containers, key-point dots) keeps
 * the scheme's own value, taken from the light or dark base by [AppPalette.isDark].
 *
 * Everything here is a plain function of the palette, not a `@Composable`: the scheme is rebuilt only
 * when the palette itself changes, and [MiuixTheme] copies it into its state holders.
 */
private fun yunyinMiuixColors(palette: AppPalette): Colors {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    val accent = palette.accent
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        primaryVariant = accent,
        onPrimaryVariant = accent.copy(alpha = 0.55f),
        primaryContainer = accent,
        onPrimaryContainer = Color.White,
        disabledPrimary = accent.copy(alpha = 0.35f),
        disabledOnPrimary = Color.White,
        disabledPrimaryButton = accent.copy(alpha = 0.35f),
        disabledOnPrimaryButton = Color.White,
        disabledPrimarySlider = accent.copy(alpha = 0.4f),
        error = AppleColors.systemRed,

        background = palette.background,
        onBackground = palette.label,
        onBackgroundVariant = palette.secondaryLabel,

        surface = palette.secondaryBackground,
        onSurface = palette.label,
        surfaceVariant = palette.tertiaryBackground,
        onSurfaceSecondary = palette.secondaryLabel,
        onSurfaceVariantSummary = palette.secondaryLabel,
        onSurfaceVariantActions = palette.tertiaryLabel,
        disabledOnSurface = palette.tertiaryLabel,

        // What a Card paints, and what its rows read their ink from.
        surfaceContainer = palette.secondaryBackground,
        onSurfaceContainer = palette.label,
        onSurfaceContainerVariant = palette.secondaryLabel,
        surfaceContainerHigh = palette.tertiaryBackground,
        onSurfaceContainerHigh = palette.secondaryLabel,
        surfaceContainerHighest = palette.tertiaryBackground,
        onSurfaceContainerHighest = palette.label,

        secondary = palette.tertiaryBackground,
        onSecondary = palette.label,
        secondaryVariant = palette.secondaryBackground,
        onSecondaryVariant = palette.label,
        disabledSecondary = palette.secondaryBackground,
        disabledOnSecondary = palette.tertiaryLabel,
        disabledSecondaryVariant = palette.tertiaryBackground,
        disabledOnSecondaryVariant = palette.tertiaryLabel,
        secondaryContainer = palette.secondaryBackground,
        onSecondaryContainer = palette.secondaryLabel,
        secondaryContainerVariant = palette.tertiaryBackground,
        onSecondaryContainerVariant = palette.secondaryLabel,
        tertiaryContainer = accent.copy(alpha = 0.12f),
        onTertiaryContainer = accent,
        tertiaryContainerVariant = palette.tertiaryBackground,

        outline = palette.separator,
        dividerLine = palette.separator.copy(alpha = 0.5f),
        windowDimming = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.3f),
        sliderKeyPoint = accent.copy(alpha = 0.3f),
        sliderKeyPointForeground = accent,
        sliderBackground = palette.label.copy(alpha = if (palette.isDark) 0.15f else 0.06f),
    )
}

/**
 * One card group: a [SmallTitle], the miuix [Card] of rows, and an optional footnote card.
 *
 * The card is inset 12 dp while the title keeps miuix's own 28 dp inside margin and each row keeps its
 * 16 dp inside margin — 12 + 16 = 28, so the group title and the row text start on the same line
 * without either component being told a magic number.
 */
@Composable
private fun SettingsGroup(
    title: String,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SmallTitle(title)
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            content()
        }
        if (footnote != null) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            ) {
                Text(
                    text = footnote,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * Hairline between two rows, inset to the row text.
 *
 * miuix draws its dividers this way on purpose: the line starts where the text starts, so a group reads
 * as one block rather than as a stack of framed cells. Only ever placed *before* a row, never after the
 * last one — which is also why the conditional rows below each start with a divider rather than the row
 * above ending with one.
 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

/** The right-hand value of a preference: quiet, one line, never wider than its share of the row. */
@Composable
private fun RowScope.PreferenceValue(
    text: String,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantActions,
) {
    Text(
        text = text,
        modifier = Modifier.align(Alignment.CenterVertically),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A row that only reports something (版本 / 接口状态 / 歌词来源).
 *
 * Knowingly not an [ArrowPreference]: that component always draws a chevron, and a chevron promises a
 * place to go. A value with nowhere to go is a [BasicComponent] — the same miuix row, minus the lie.
 *
 * A short value (a version, a licence) sits on the right as [value]; anything that is a sentence goes
 * through [summary] instead, because the right-hand slot is capped at a fraction of the row's width
 * and a one-line ellipsis would cut the diagnostic off exactly where it starts being useful.
 */
@Composable
private fun InfoPreference(
    title: String,
    value: String? = null,
    summary: String? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = value?.let { text -> { PreferenceValue(text) } },
    )
}

/** A row whose tap does something in place (clear a cache, copy a log, send a test lyric). */
@Composable
private fun ActionPreference(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
    )
}

/**
 * Status of the 词幕 (Lyricon) bridge.
 *
 * Three distinct states, each with different advice, which is why this is not a plain on/off row:
 * connected (nothing to do), installed but not connected (offer a retry), and not installed (name the
 * app the user needs, since nothing else in 云音 would ever mention it). A fourth state is the switch
 * above being off, in which case there is nothing to report and the row says so rather than showing a
 * stale "未连接".
 */
@Composable
private fun LyriconPreference(
    enabled: Boolean,
    connected: Boolean,
    available: Boolean,
    onRetry: () -> Unit,
) {
    val (status, hint) = when {
        !enabled -> "已关闭" to "打开上面的开关即可启用"
        connected -> "已连接" to "歌词会显示在词幕的状态栏"
        available -> "未连接" to "点击重试连接"
        else -> "未安装" to "需先安装「词幕」应用"
    }
    val statusColor = if (connected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val retryable = enabled && available && !connected

    // Only when there is something to retry does the row become an arrow (a tap that acts). Otherwise
    // it is a report, and a chevron on a report is the "nothing happens when I tap" bug in advance.
    if (retryable) {
        ArrowPreference(
            title = "连接状态",
            summary = hint,
            endActions = { PreferenceValue(status, color = statusColor) },
            onClick = onRetry,
        )
    } else {
        BasicComponent(
            title = "连接状态",
            summary = hint,
            endActions = { PreferenceValue(status, color = statusColor) },
        )
    }
}

/**
 * What the "检查更新" row shows on its right-hand side.
 *
 * Each state gets its own wording because they are genuinely different situations, and collapsing them is
 * how an updater ends up telling someone "已是最新" when it never managed to ask. A failed check says so,
 * and stays tappable so the user can retry.
 */
private fun updateRowValue(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Idle -> "点击检查"
    is UpdateUiState.Available -> "发现 ${state.update.versionName}"
    is UpdateUiState.AvailableSkipped -> "有新版本 ${state.update.versionName}"
    is UpdateUiState.Downloading -> if (state.progress in 0..100) "下载中 ${state.progress}%" else "下载中…"
    is UpdateUiState.Failed -> "检查失败"
}

/** A licence: the name on the left, the identifier on the right, the upstream repository behind a tap. */
@Composable
private fun LicensePreference(name: String, license: String, url: String) {
    val uriHandler = LocalUriHandler.current
    ArrowPreference(
        title = name,
        endActions = { PreferenceValue(license) },
        onClick = { runCatching { uriHandler.openUri(url) } },
    )
}

/**
 * The 音源 section of Settings: import a script, then manage what was imported.
 *
 * ## Why the list is inline rather than a sheet
 *
 * The set is small (a handful of sources), and the whole point of the section is to answer "what is
 * installed and which one is on". A sheet would hide that behind a tap; three rows say it at a glance,
 * in the same visual language as every other settings row.
 *
 * ## The rule shown at the bottom
 *
 * At most **one enabled source may claim a given source key** (`kw`/`kg`/`tx`/`wy`/`mg`/`local`), because two
 * scripts answering for the same provider would make every later request ambiguous. The enforcement lives in
 * `LxSourceStore`; the line here exists so the refusal is explainable rather than surprising. A newly imported
 * source starts enabled when no other enabled script claims its keys, and stays disabled (with the reason in
 * [message]) when one does.
 */
@Composable
private fun LxSourcesGroup(
    sources: List<LxImportedSource>,
    importing: Boolean,
    message: String?,
    errorLogAvailable: Boolean,
    onCopyErrorLog: () -> Unit,
    onImport: () -> Unit,
    onImportFromUrl: () -> Unit,
    onRefresh: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Every state line this section can produce, gathered into the group's footnote: one card of
    // quiet text under the rows, instead of a stack of framed hint rows inside them.
    val notes = buildList {
        if (importing) add("正在运行脚本并等待它发送 inited 事件…")
        if (message != null) add(message)
        if (sources.isEmpty() && !importing) {
            add("尚未导入音源脚本。支持 LX Music（洛雪音乐）自定义源，导入时会校验脚本能否初始化。")
        }
        if (sources.size > 1) {
            add("同一个音源标识（如 wy）只能启用一个脚本，避免同一个平台由两个脚本同时应答。")
        }
        if (sources.any { it.enabled && LxSourceKey.WY in it.keys }) {
            add("已启用的音源会优先解析网易云播放链接；解析失败或超时会自动回退到内置接口。")
        }
    }

    SettingsGroup(
        title = "音源",
        footnote = notes.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
    ) {
        ArrowPreference(
            title = "导入自定义音源",
            summary = "选择本地 .js 脚本并校验",
            endActions = { if (importing) PreferenceValue("校验中…") },
            enabled = !importing,
            onClick = onImport,
        )
        GroupDivider()
        ArrowPreference(
            title = "从链接导入",
            summary = "粘贴网络音源地址",
            endActions = { if (importing) PreferenceValue("校验中…") },
            enabled = !importing,
            onClick = onImportFromUrl,
        )
        if (errorLogAvailable) {
            GroupDivider()
            ActionPreference(
                title = "复制音源错误日志",
                summary = "包含脚本失败时的详细调用栈",
                onClick = onCopyErrorLog,
            )
        }
        sources.forEach { source ->
            GroupDivider()
            LxSourcePreference(
                source = source,
                onToggle = { enabled -> onToggle(source.id, enabled) },
                onRefresh = if (source.originUrl != null) ({ onRefresh(source.id) }) else null,
                onDelete = { onDelete(source.id) },
            )
        }
    }
}

/** One imported source: name and its declaration, the enable switch, and its two maintenance actions. */
@Composable
private fun LxSourcePreference(
    source: LxImportedSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** Present only for a URL-sourced script, whose origin can be re-fetched. */
    onRefresh: (() -> Unit)? = null,
) {
    SwitchPreference(
        checked = source.enabled,
        onCheckedChange = onToggle,
        title = source.header.name,
        summary = buildString {
            append("支持 ").append(source.keysLabel)
            source.versionAuthorLabel.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            // The origin rides along in the summary rather than as a third line of its own: a miuix
            // preference has exactly one summary, and an unreachable third line would be a layout we
            // would have to hand-roll back.
            source.originUrl?.let { append('\n').append(it) }
        },
        bottomAction = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRefresh != null) {
                    TextButton(
                        text = "更新",
                        onClick = onRefresh,
                        minWidth = 0.dp,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    // Destructive, so it says so in colour even though both actions are the same shape.
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                    insideMargin = PaddingValues(horizontal = 12.dp),
                )
            }
        },
    )
}
