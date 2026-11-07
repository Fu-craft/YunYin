// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
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
import com.yunyin.music.audio.EqualizerPreset
import com.yunyin.music.audio.EqualizerSettings
import com.yunyin.music.data.lxsource.LxImportedSource
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.update.UpdateUiState
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
 * The screens this list can show, in navigation order.
 *
 * The main page is deliberately short — six sections, each answering "how does playback behave". The
 * four maintenance screens (音源 / 许可 / 更新 / 崩溃日志) are *pages*, not sections: they are visited
 * rarely, they are long, and mixing them into the main list made it a wall of rows where the choices
 * people actually change (无缝衔接, 桌面歌词) sat next to licence boilerplate.
 */
internal enum class SettingsPage(val title: String) {
    Main("设置"),
    LxSources("音源管理"),
    Licenses("开源许可"),
    Update("软件更新"),
    Logs("崩溃日志"),
    Effects("音效"),
}

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
 * The page is built from Miuix preferences rather than hand-rolled rows: **one [Card] per section**,
 * every row an [ArrowPreference] (a tap that goes somewhere), a [SwitchPreference] (a value with two
 * states) or a [SliderPreference] (a value on a scale). Cards are separated by a [SectionHeader] —
 * 14 sp, 0.2 sp tracking, muted ink — instead of a second card per section: the section note lives
 * *inside* the card as muted text, because a note in its own card reads as another group of settings.
 *
 * The whole screen is wrapped in [MiuixTheme], whose color scheme is *derived from* `AppTheme.palette`
 * rather than replacing it (see [yunyinMiuixColors]). Row subtitles therefore get the muted role
 * (`onSurfaceVariantSummary` → the palette's secondary label) from the theme itself, so no row has to
 * remember to be quiet.
 *
 * ## Why a LazyColumn
 *
 * The lists are long (the licence page alone is a dozen rows) and every page shares the same scroll
 * contract under the frosted top bar; a lazily composed list keeps that cheap, and each page keeps its
 * own scroll position through a [androidx.compose.runtime.saveable.SaveableStateHolder] so coming back
 * from a subpage returns you where you left.
 */
@Composable
fun SettingsScreen(
    onClearLyricsCache: () -> Unit,
    onBack: () -> Unit,
    /** Opens the download centre. */
    onOpenDownloads: () -> Unit = {},
    /** Lyric type multiplier (0.8-1.6). */
    lyricFontScale: Float = 1f,
    /** 界面风格。两套实现并存,这里只决定用哪一份。 */
    uiStyle: com.yunyin.music.ui.style.UiStyle = com.yunyin.music.ui.style.UiStyle.Classic,
    onUiStyleChange: (com.yunyin.music.ui.style.UiStyle) -> Unit = {},
    onLyricFontScaleChange: (Float) -> Unit = {},
    /** 打开「听歌足迹」。 */
    /** Opens the listening footprint screen. */
    onOpenFootprint: () -> Unit = {},
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
    /** Platform equaliser on this app's own audio session. */
    equalizerEnabled: Boolean = false,
    onEqualizerEnabledChange: (Boolean) -> Unit = {},
    /** Preset name (`FLAT`/`BASS`/`VOCAL`/`ROCK`/`POP`/`JAZZ`). */
    equalizerPreset: String = "FLAT",
    onEqualizerPresetChange: (String) -> Unit = {},
    /** Five band gains in millibels, 60/230/910/3600/14000 Hz order. */
    equalizerBands: List<Int> = listOf(0, 0, 0, 0, 0),
    onEqualizerBandChange: (Int, Int) -> Unit = { _, _ -> },
    /**
     * CurrentMusic 账号区块；由宿主注入，避免这里依赖 SettingsStore。
     *
     * Nullable rather than an empty lambda on purpose: a section that renders a card around content
     * nobody injected is an empty card, so the whole 一起听 section is absent until a host provides it.
     * The injected content is expected to be plain miuix rows — it is placed *inside* the section card.
     */
    currentMusicSection: (@Composable () -> Unit)? = null,
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
     * Drives both the "崩溃日志" entry on the About card and the page behind it: the app writes every
     * uncaught exception to `filesDir/crash-last.txt`, but that file is unreachable without adb on an
     * unrooted device — and a launch crash makes adb the *only* other route, which is useless to the
     * person holding the phone.
     */
    hasCrashLog: Boolean = false,
    /** Copies the recorded crash text to the clipboard so it can be pasted into a message. */
    onCopyCrashLog: () -> Unit = {},
    /**
     * The update check's state, for the "检查更新" entry.
     *
     * A whole state rather than a boolean because the row has to say four different things — checking,
     * current, a version is available, and a failed check — and an updater that cannot distinguish "no
     * update" from "could not ask" is one that lies to the user about being current.
     */
    updateState: UpdateUiState = UpdateUiState.Idle,
    /** Hides the entry entirely in a build with no update repository configured. */
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
    /** The result of the last import or toggle, shown under the 音源管理 rows. */
    lxImportMessage: String? = null,
    /**
     * Whether a custom-source failure detail has been recorded.
     *
     * The short reason is in [lxImportMessage]; the JS stack that explains *why* is in a file, and on an
     * unrooted device that file is unreachable without adb — the same problem the crash-log page solves.
     * The row therefore appears only when there is something to copy.
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
    // composed outside it (after the content box closes, below).
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    // Per-page saveable state: without it, stepping into 开源许可 and coming back would drop the main
    // list back to the top — the one thing a back gesture must never cost.
    val pageStates = rememberSaveableStateHolder()
    val miuixColors = remember(palette) { yunyinMiuixColors(palette) }
    // Typography is set where the components read it. Overriding miuix's scale here beats passing a
    // size to every row: row title 16 sp (headline1) and row subtitle 12.5 sp (body2) are the two roles
    // docs/SETTINGS-STYLE.md §3 names, applied once. Only the *sizes* are reachable this way — miuix's
    // BasicComponent reads `headline1.fontSize` / `body2.fontSize` and inherits tracking and line height
    // from its `main` style, so per-role tracking would mean re-implementing the row. Not worth it for
    // ±0.1 sp.
    val baseTextStyles = MiuixTheme.textStyles
    val miuixTextStyles = remember(baseTextStyles) {
        baseTextStyles.copy(
            headline1 = baseTextStyles.headline1.copy(
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.1).sp,
            ),
            body2 = baseTextStyles.body2.copy(
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.1.sp,
            ),
        )
    }

    // Back means "up one level", not "close Settings": see [SubpageBackHandler] for why this is not a
    // plain BackHandler.
    SubpageBackHandler(enabled = page != SettingsPage.Main) { page = SettingsPage.Main }

    MiuixTheme(colors = miuixColors, textStyles = miuixTextStyles) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // The screen is a full-window overlay and is not wrapped in a Surface by its host, so it
                // has to paint its own page background; the recorded layer above paints the same colour
                // *into the blur field* (that fill is what the top strip's blur samples).
                .background(palette.background),
        ) {
            Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
                pageStates.SaveableStateProvider(page) {
                    when (page) {
                        SettingsPage.Main -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            // COUI 版主页:与下面的 miuix 版并存,由设置里的风格开关选择。
                            CouiSettingsMainPage(
                                uiStyle = uiStyle,
                                onUiStyleChange = onUiStyleChange,
                                sleepTimerLabel = sleepTimerLabel,
                                onShowSleepTimer = onShowSleepTimer,
                                onOpenFootprint = onOpenFootprint,
                                onShowPlayerStyle = onShowPlayerStyle,
                                playlistShowcaseLabel = playlistShowcaseLabel,
                                onCyclePlaylistShowcase = onCyclePlaylistShowcase,
                                onOpenLxSources = { page = SettingsPage.LxSources },
                                onOpenEffects = { page = SettingsPage.Effects },
                                onOpenLicenses = { page = SettingsPage.Licenses },
                                onOpenUpdate = { page = SettingsPage.Update },
                                onOpenLogs = { page = SettingsPage.Logs },
                                hasCrashLog = hasCrashLog,
                                onOpenDownloads = onOpenDownloads,
                                downloadWifiOnly = downloadWifiOnly,
                                onDownloadWifiOnlyChange = onDownloadWifiOnlyChange,
                                onClearLyricsCache = onClearLyricsCache,
                                onOpenProject = onOpenProject,
                                nowPlayingCoverBlurBackground = nowPlayingCoverBlurBackground,
                                onNowPlayingCoverBlurBackgroundChange = onNowPlayingCoverBlurBackgroundChange,
                                nowPlayingAudioReactive = nowPlayingAudioReactive,
                                onNowPlayingAudioReactiveChange = onNowPlayingAudioReactiveChange,
                                nowPlayingDynamicBackground = nowPlayingDynamicBackground,
                                onNowPlayingDynamicBackgroundChange = onNowPlayingDynamicBackgroundChange,
                                animatedCoverEnabled = animatedCoverEnabled,
                                onAnimatedCoverChange = onAnimatedCoverChange,
                            )
                        } else {
                        MainPage(
                            page = page,
                            onNavigate = { page = it },
                            onClearLyricsCache = onClearLyricsCache,
                            onOpenDownloads = onOpenDownloads,
                            onOpenFootprint = onOpenFootprint,
                            downloadWifiOnly = downloadWifiOnly,
                            onDownloadWifiOnlyChange = onDownloadWifiOnlyChange,
                            onOpenProject = onOpenProject,
                            lyriconConnected = lyriconConnected,
                            lyriconAvailable = lyriconAvailable,
                            onRetryLyricon = onRetryLyricon,
                            lyriconEnabled = lyriconEnabled,
                            onLyriconEnabledChange = onLyriconEnabledChange,
                            flymeSupported = flymeSupported,
                            statusBarLyrics = statusBarLyrics,
                            onStatusBarLyricsChange = onStatusBarLyricsChange,
                            desktopLyricsEnabled = desktopLyricsEnabled,
                            onDesktopLyricsEnabledChange = onDesktopLyricsEnabledChange,
                            overlayPermissionGranted = overlayPermissionGranted,
                            onRequestOverlayPermission = onRequestOverlayPermission,

                            onOpenEffects = { page = SettingsPage.Effects },
                            currentMusicSection = currentMusicSection,
                            playlistShowcaseLabel = playlistShowcaseLabel,
                            onCyclePlaylistShowcase = onCyclePlaylistShowcase,
                            onTestFlymeLyric = onTestFlymeLyric,
                            autoImmersiveLyrics = autoImmersiveLyrics,
                            onAutoImmersiveLyricsChange = onAutoImmersiveLyricsChange,
                            nowPlayingCoverBlurBackground = nowPlayingCoverBlurBackground,
                            onNowPlayingCoverBlurBackgroundChange = onNowPlayingCoverBlurBackgroundChange,
                            nowPlayingAudioReactive = nowPlayingAudioReactive,
                            onNowPlayingAudioReactiveChange = onNowPlayingAudioReactiveChange,
                            nowPlayingDynamicBackground = nowPlayingDynamicBackground,
                            onNowPlayingDynamicBackgroundChange = onNowPlayingDynamicBackgroundChange,
                            nowPlayingBackgroundStyle = nowPlayingBackgroundStyle,
                            onShowBackgroundStyle = onShowBackgroundStyle,
                            animatedCoverAvailable = animatedCoverAvailable,
                            animatedCoverEnabled = animatedCoverEnabled,
                            onAnimatedCoverChange = onAnimatedCoverChange,
                            onTestAnimatedCover = onTestAnimatedCover,
                            sleepTimerLabel = sleepTimerLabel,
                            onShowSleepTimer = onShowSleepTimer,
                            hasCrashLog = hasCrashLog,
                            updateState = updateState,
                            updateConfigured = updateConfigured,
                            playerStyle = playerStyle,
                            onShowPlayerStyle = onShowPlayerStyle,
                            onShowCoverMotionTuner = onShowCoverMotionTuner,
                            lxSourceCount = lxSources.size,
                            lyricFontScale = lyricFontScale,
                            onLyricFontScaleChange = onLyricFontScaleChange,
                            uiStyle = uiStyle,
                            onUiStyleChange = onUiStyleChange,
                        )
                        }

                        SettingsPage.LxSources -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            CouiLxSourcesPage(
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
                        } else LxSourcesPage(
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

                        SettingsPage.Licenses -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            CouiLicensesPage()
                        } else {
                            LicensesPage()
                        }

                        SettingsPage.Update -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            CouiUpdatePage(state = updateState, onCheckUpdate = onCheckUpdate)
                        } else {
                            UpdatePage(
                                state = updateState,
                                onCheckUpdate = onCheckUpdate,
                            )
                        }

                        SettingsPage.Logs -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            CouiLogsPage(
                                hasCrashLog = hasCrashLog,
                                onCopyCrashLog = onCopyCrashLog,
                            )
                        } else {
                            LogsPage(
                                hasCrashLog = hasCrashLog,
                                onCopyCrashLog = onCopyCrashLog,
                            )
                        }

                        SettingsPage.Effects -> if (uiStyle == com.yunyin.music.ui.style.UiStyle.Coui) {
                            CouiEffectsPage(
                                enabled = equalizerEnabled,
                                onEnabledChange = onEqualizerEnabledChange,
                                preset = equalizerPreset,
                                onPresetChange = onEqualizerPresetChange,
                                bands = equalizerBands,
                                onBandChange = onEqualizerBandChange,
                            )
                        } else {
                            EffectsPage(
                                enabled = equalizerEnabled,
                                onEnabledChange = onEqualizerEnabledChange,
                                preset = equalizerPreset,
                                onPresetChange = onEqualizerPresetChange,
                                bands = equalizerBands,
                                onBandChange = onEqualizerBandChange,
                            )
                        }
                    }
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
                    // 44dp for a comfortable touch target; the glyph stays 22dp so the header reads the
                    // same. On a subpage this arrow goes *up* (to the main list), which is the same
                    // control the system back gesture drives — see the BackHandler above.
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (page == SettingsPage.Main) onBack() else page = SettingsPage.Main
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            SfIcons.ChevronLeft,
                            contentDescription = if (page == SettingsPage.Main) "返回" else "返回设置",
                            // Navigation chrome stays neutral: the accent is spent on switches, danger
                            // and links, so a back arrow tinted with it would be the fourth accent.
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Main page — six sections, each one a single card
// ---------------------------------------------------------------------------------------------

@Composable
private fun MainPage(
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    onClearLyricsCache: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenFootprint: () -> Unit,
    downloadWifiOnly: Boolean,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onOpenProject: () -> Unit,
    lyriconConnected: Boolean,
    lyriconAvailable: Boolean,
    onRetryLyricon: () -> Unit,
    lyriconEnabled: Boolean,
    onLyriconEnabledChange: (Boolean) -> Unit,
    flymeSupported: Boolean,
    statusBarLyrics: Boolean,
    onStatusBarLyricsChange: (Boolean) -> Unit,
    desktopLyricsEnabled: Boolean,
    onDesktopLyricsEnabledChange: (Boolean) -> Unit,
    overlayPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,

    onOpenEffects: () -> Unit,
    currentMusicSection: (@Composable () -> Unit)?,
    playlistShowcaseLabel: String,
    onCyclePlaylistShowcase: () -> Unit,
    onTestFlymeLyric: () -> Unit,
    autoImmersiveLyrics: Boolean,
    onAutoImmersiveLyricsChange: (Boolean) -> Unit,
    nowPlayingCoverBlurBackground: Boolean,
    onNowPlayingCoverBlurBackgroundChange: (Boolean) -> Unit,
    nowPlayingAudioReactive: Boolean,
    onNowPlayingAudioReactiveChange: (Boolean) -> Unit,
    nowPlayingDynamicBackground: Boolean,
    onNowPlayingDynamicBackgroundChange: (Boolean) -> Unit,
    nowPlayingBackgroundStyle: BackgroundStyle,
    onShowBackgroundStyle: () -> Unit,
    animatedCoverAvailable: Boolean,
    animatedCoverEnabled: Boolean,
    onAnimatedCoverChange: (Boolean) -> Unit,
    onTestAnimatedCover: (() -> Unit)?,
    sleepTimerLabel: String,
    onShowSleepTimer: () -> Unit,
    hasCrashLog: Boolean,
    updateState: UpdateUiState,
    updateConfigured: Boolean,
    playerStyle: PlayerStyle,
    onShowPlayerStyle: () -> Unit,
    onShowCoverMotionTuner: () -> Unit,
    lxSourceCount: Int,
    lyricFontScale: Float,
    onLyricFontScaleChange: (Float) -> Unit,
    uiStyle: com.yunyin.music.ui.style.UiStyle,
    onUiStyleChange: (com.yunyin.music.ui.style.UiStyle) -> Unit,
) {
    val listState = rememberLazyListState()
    SettingsPageList(
        title = page.title,
        state = listState,
    ) {
        // 0 — 界面风格。两边都在:经典版能切到凝光,凝光版也能切回来。
        item(key = "ui-style") {
            SettingsSection(
                title = "界面风格",
                note = "两套组件各自实现,切换后设置页立即换用另一份;已保存的偏好不受影响。",
            ) {
                com.yunyin.music.ui.style.UiStyle.entries.forEachIndexed { index, style ->
                    if (index > 0) GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = style.label,
                        summary = style.summary,
                        endActions = {
                            if (style == uiStyle) PreferenceValue("使用中")
                        },
                        onClick = { onUiStyleChange(style) },
                    )
                }
            }
        }
        // 1 — 播放
        item(key = "play") {
            SettingsSection(title = "播放") {
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "定时关闭",
                    summary = "到点后自动停止播放",
                    endActions = { PreferenceValue(sleepTimerLabel) },
                    onClick = onShowSleepTimer,
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "听歌足迹",
                    summary = "最近 42 天的收听热力图与每日明细",
                    onClick = onOpenFootprint,
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "播放器界面",
                    summary = "播放页展示封面或唱片",
                    endActions = { PreferenceValue(playerStyle.label) },
                    onClick = onShowPlayerStyle,
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "\u6B4C\u8BCD\u5B57\u53F7",
                    summary = "\u6ED1\u52A8\u5FAA\u73AF\uFF1A\u5C0F \u2192 \u5927\uFF08\u5F53\u524D \u00D7" + String.format("%.1f", lyricFontScale) + "\uFF09",
                    endActions = { PreferenceValue(String.format("\u00D7%.1f", lyricFontScale)) },
                    onClick = {
                        val steps = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f)
                        val next = steps.firstOrNull { it > lyricFontScale + 0.01f } ?: steps.first()
                        onLyricFontScaleChange(next)
                    },
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "歌单展示",
                    summary = "首页歌单用立体缎带或CD唱片架",
                    endActions = { PreferenceValue(playlistShowcaseLabel) },
                    onClick = onCyclePlaylistShowcase,
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "封面动效手感",
                    summary = "调整封面缩小与回弹的软硬",
                    endActions = { PreferenceValue("暂停与恢复的弹簧") },
                    onClick = onShowCoverMotionTuner,
                )
                GroupDivider()
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = autoImmersiveLyrics,
                    onCheckedChange = onAutoImmersiveLyricsChange,
                    title = "自动进入沉浸歌词",
                    summary = "歌词页闲置几秒后隐藏播放控件",
                )
                GroupDivider()
                // 音源 decides where playback resolves a stream from, so it belongs with the playback
                // choices rather than in the About junk drawer — but its rows are long enough (import,
                // per-source switches, log copying) to live on their own page.
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "音源管理",
                    summary = "导入与启用自定义音源脚本",
                    endActions = {
                        PreferenceValue(if (lxSourceCount == 0) "未导入" else "$lxSourceCount 个")
                    },
                    onClick = { onNavigate(SettingsPage.LxSources) },
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "音效",
                    summary = "均衡器与预设（系统 Equalizer）",
                    onClick = onOpenEffects,
                )
            }
        }

        // 2 — 一起听 (host-injected; absent entirely when no host provides it)
        if (currentMusicSection != null) {
            item(key = "together") {
                SettingsSection(title = "一起听") {
                    currentMusicSection()
                }
            }
        }

        // 3 — 正在播放外观
        item(key = "nowPlaying") {
            SettingsSection(title = "正在播放外观") {
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = nowPlayingCoverBlurBackground,
                    onCheckedChange = onNowPlayingCoverBlurBackgroundChange,
                    title = "模糊封面背景",
                    summary = "用当前封面作为播放页背景",
                )
                GroupDivider()
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = nowPlayingAudioReactive,
                    onCheckedChange = onNowPlayingAudioReactiveChange,
                    title = "音频律动",
                    summary = "背景随音频节奏变化",
                )
                GroupDivider()
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = nowPlayingDynamicBackground,
                    onCheckedChange = onNowPlayingDynamicBackgroundChange,
                    title = "动态背景",
                    summary = "显示随时间变化的动态背景",
                )
                if (animatedCoverAvailable) {
                    GroupDivider()
                    SwitchPreference(
                        insideMargin = RowInsets,
                        checked = animatedCoverEnabled,
                        onCheckedChange = onAnimatedCoverChange,
                        title = "动态封面",
                        // Naming the host makes it obvious which endpoint this build resolves against,
                        // so "the toggle is on but nothing moves" can be told apart from "this build has
                        // no artwork endpoint at all".
                        summary = "为当前歌曲显示服务端动态封面 · " +
                            com.yunyin.music.BuildConfig.ANIMATED_ARTWORK_BASE_URL
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .trimEnd('/') +
                            " · v${com.yunyin.music.BuildConfig.VERSION_NAME}",
                    )
                    if (animatedCoverEnabled) {
                        // What the last track lookup actually did: no request, 404, 5xx, a hit whose
                        // stream the player rejected, or a hit that is now playing.
                        val artworkStatus by com.yunyin.music.data.AnimatedArtworkDiagnostics
                            .status.collectAsState()
                        GroupDivider()
                        // A sentence, not a value: it goes in the summary so it can wrap instead of
                        // being ellipsised in the narrow end slot.
                        InfoPreference(title = "接口状态", summary = artworkStatus)
                        if (onTestAnimatedCover != null) {
                            GroupDivider()
                            ArrowPreference(
                                insideMargin = RowInsets,
                                title = "测试接口",
                                summary = "用 Taylor Swift《Lover》查一次，验证网络与接口",
                                onClick = onTestAnimatedCover,
                            )
                        }
                    }
                }
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "背景样式",
                    summary = "选择正在播放页的动态背景",
                    endActions = { PreferenceValue(nowPlayingBackgroundStyle.label) },
                    onClick = onShowBackgroundStyle,
                )
            }
        }

        // 4 — 状态栏歌词
        item(key = "statusBar") {
            SettingsSection(
                title = "状态栏歌词",
                note = "词幕需已安装并在系统中启用；Flyme 歌词只在对应系统上可用。",
            ) {
                SwitchPreference(
                    insideMargin = RowInsets,
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
                    insideMargin = RowInsets,
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
                    insideMargin = RowInsets,
                    checked = desktopLyricsEnabled,
                    onCheckedChange = onDesktopLyricsEnabledChange,
                    title = "桌面歌词",
                    summary = "在其他应用上方显示当前歌词",
                )
                if (desktopLyricsEnabled && !overlayPermissionGranted) {
                    GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = "悬浮窗权限",
                        summary = "允许云音显示在其他应用上方",
                        endActions = { PreferenceValue("未授权", color = MiuixTheme.colorScheme.error) },
                        onClick = onRequestOverlayPermission,
                    )
                }
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "测试状态栏歌词",
                    summary = "发送一条测试歌词到状态栏",
                    onClick = onTestFlymeLyric,
                )
            }
        }

        // 5 — 下载与存储
        item(key = "storage") {
            SettingsSection(
                title = "下载与存储",
                note = "清除歌词缓存不会删除本地音乐或已下载的歌曲。",
            ) {
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "下载中心",
                    summary = "查看进行中、已完成与失败的下载",
                    onClick = onOpenDownloads,
                )
                GroupDivider()
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = downloadWifiOnly,
                    onCheckedChange = onDownloadWifiOnlyChange,
                    title = "仅 Wi-Fi 下载",
                    summary = "避免用移动数据开始下载",
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "清除歌词缓存",
                    summary = "删除已下载的歌词文件",
                    onClick = onClearLyricsCache,
                )
            }
        }

        // 6 — 关于: the hub for everything that is not a playback choice
        item(key = "about") {
            SettingsSection(title = "关于") {
                InfoPreference(title = "版本", value = com.yunyin.music.BuildConfig.VERSION_NAME)
                if (updateConfigured) {
                    GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = "检查更新",
                        summary = "从 GitHub Release 获取新版本",
                        endActions = { PreferenceValue(updateRowValue(updateState)) },
                        onClick = { onNavigate(SettingsPage.Update) },
                    )
                }
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "开源许可",
                    summary = "本项目与全部上游组件的许可证",
                    onClick = { onNavigate(SettingsPage.Licenses) },
                )
                if (hasCrashLog) {
                    GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = "崩溃日志",
                        summary = "复制最近一次崩溃的调用栈",
                        onClick = { onNavigate(SettingsPage.Logs) },
                    )
                }
                GroupDivider()
                InfoPreference(title = "歌词来源", value = "AMLL 逐字歌词")
                GroupDivider()
                // External: this one leaves the app, which the chevron alone would not say.
                ExternalPreference(
                    title = "项目主页",
                    summary = "在浏览器中打开 GitHub 仓库",
                    value = "GitHub",
                    onClick = onOpenProject,
                )
            }
        }

        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

// ---------------------------------------------------------------------------------------------
// Sub-pages
// ---------------------------------------------------------------------------------------------

/**
 * 音源管理: import a script, then manage what was imported.
 *
 * ## Why the list is inline rather than a sheet
 *
 * The set is small (a handful of sources), and the whole point of the page is to answer "what is
 * installed and which one is on". A sheet would hide that behind a tap; the rows say it at a glance.
 *
 * ## The rule shown at the bottom
 *
 * At most **one enabled source may claim a given source key** (`kw`/`kg`/`tx`/`wy`/`mg`/`local`),
 * because two scripts answering for the same provider would make every later request ambiguous. The
 * enforcement lives in `LxSourceStore`; the note here exists so the refusal is explainable rather than
 * surprising. A newly imported source starts enabled when no other enabled script claims its keys, and
 * stays disabled (with the reason in [message]) when one does.
 */
@Composable
private fun LxSourcesPage(
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

    SettingsPageList(title = SettingsPage.LxSources.title, state = rememberLazyListState()) {
        item(key = "import") {
            SettingsSection(
                title = "导入",
                note = notes.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            ) {
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "导入自定义音源",
                    summary = "选择本地 .js 脚本并校验",
                    endActions = { if (importing) PreferenceValue("校验中…") },
                    enabled = !importing,
                    onClick = onImport,
                )
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "从链接导入",
                    summary = "粘贴网络音源地址",
                    endActions = { if (importing) PreferenceValue("校验中…") },
                    enabled = !importing,
                    onClick = onImportFromUrl,
                )
            }
        }

        if (sources.isNotEmpty()) {
            item(key = "installed") {
                // Only sources that exist get a card: an "已导入" card with no rows would be an empty
                // frame, and the "nothing imported yet" case is already handled by the note above.
                SettingsSection(title = "已导入（${sources.size}）") {
                    sources.forEachIndexed { index, source ->
                        if (index > 0) GroupDivider()
                        LxSourcePreference(
                            source = source,
                            onToggle = { enabled -> onToggle(source.id, enabled) },
                            onRefresh = if (source.originUrl != null) {
                                ({ onRefresh(source.id) })
                            } else {
                                null
                            },
                            onDelete = { onDelete(source.id) },
                        )
                    }
                    if (errorLogAvailable) {
                        GroupDivider()
                        ArrowPreference(
                            insideMargin = RowInsets,
                            title = "复制音源错误日志",
                            summary = "包含脚本失败时的详细调用栈",
                            onClick = onCopyErrorLog,
                        )
                    }
                }
            }
        }

        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

/** 开源许可: every component this app ships, with the identifier and the upstream it came from. */
@Composable
private fun LicensesPage() {
    val uriHandler = LocalUriHandler.current
    val licenses = remember {
        listOf(
            Triple("云音（本项目）", "AGPL-3.0-only", "$PROJECT_URL/blob/master/LICENSE"),
            Triple(
                "AMLL / Mesh Gradient",
                "AGPL-3.0-only",
                "https://github.com/amll-dev/applemusic-like-lyrics",
            ),
            Triple("QPlayer", "Apache-2.0", "https://github.com/TIMER-err/QPlayer"),
            Triple("NeriPlayer", "GPL-3.0", "https://github.com/cwuom/NeriPlayer"),
            Triple(
                "accompanist lyrics-core",
                "Apache-2.0",
                "https://github.com/6xingyv/Accompanist-Lyrics",
            ),
            Triple(
                "accompanist lyrics-ui（vendored）",
                "Apache-2.0",
                "https://github.com/6xingyv/Accompanist-Lyrics",
            ),
            Triple(
                "io.github.kyant0:backdrop",
                "Apache-2.0",
                "https://github.com/Kyant0/AndroidLiquidGlass",
            ),
            Triple(
                "top.yukonga.miuix.kmp",
                "Apache-2.0",
                "https://github.com/miuix-kotlin-multiplatform/miuix",
            ),
            Triple("androidx.media3", "Apache-2.0", "https://github.com/androidx/media"),
            Triple("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
            Triple("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
            Triple("QuickJS / quickjs-kt", "Apache-2.0", "https://github.com/dokar3/quickjs-kt"),
            Triple(
                "Material Symbols",
                "Apache-2.0",
                "https://github.com/google/material-design-icons",
            ),
            Triple("Lucide（FolderMusic 图标）", "ISC", "https://github.com/lucide-icons/lucide"),
        )
    }

    SettingsPageList(title = SettingsPage.Licenses.title, state = rememberLazyListState()) {
        item(key = "licenses") {
            SettingsSection(
                title = "组件与许可证",
                note = "点击任意一行可打开上游仓库；完整声明见仓库 NOTICE。",
            ) {
                licenses.forEachIndexed { index, (name, license, url) ->
                    if (index > 0) GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = name,
                        endActions = { PreferenceValue(license) },
                        onClick = { runCatching { uriHandler.openUri(url) } },
                    )
                }
            }
        }
        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

/**
 * 软件更新.
 *
 * The actual offer (download / open the release page / skip) lives in `UpdateSheet`, which the host
 * shows on its own whenever a release is available — so this page's job is to explain *what* the
 * channel is and to trigger a check, not to duplicate the sheet as a list of rows.
 */
@Composable
private fun UpdatePage(
    state: UpdateUiState,
    onCheckUpdate: () -> Unit,
) {
    val notes = when (state) {
        is UpdateUiState.Available -> state.update.notes
        is UpdateUiState.AvailableSkipped -> state.update.notes
        is UpdateUiState.Downloading -> state.update.notes
        is UpdateUiState.Failed -> state.update?.notes
        is UpdateUiState.Idle -> null
    }
    val failure = (state as? UpdateUiState.Failed)?.message

    SettingsPageList(title = SettingsPage.Update.title, state = rememberLazyListState()) {
        item(key = "version") {
            SettingsSection(
                title = "版本",
                note = "更新包从 GitHub Release 下载，安装时由系统弹窗确认；应用不会自行安装。",
            ) {
                InfoPreference(
                    title = "当前版本",
                    value = com.yunyin.music.BuildConfig.VERSION_NAME,
                )
                GroupDivider()
                InfoPreference(title = "检查状态", value = updateRowValue(state))
                if (failure != null) {
                    GroupDivider()
                    InfoPreference(title = "失败原因", summary = failure)
                }
                GroupDivider()
                ArrowPreference(
                    insideMargin = RowInsets,
                    title = "立即检查",
                    summary = "向 GitHub 查询最新 Release",
                    onClick = onCheckUpdate,
                )
            }
        }

        if (!notes.isNullOrBlank()) {
            item(key = "notes") {
                // Release notes are content, not chrome: full label colour, readable line height, and
                // as many lines as the author wrote.
                SettingsSection(title = "更新说明") {
                    Text(
                        text = notes,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

/**
 * 崩溃日志.
 *
 * A page rather than a row because the row only made sense once something had crashed — and a page
 * can also say *why* the log exists here at all (an app-private file is unreachable without adb, which
 * is exactly the situation after a launch crash).
 */
@Composable
private fun LogsPage(
    hasCrashLog: Boolean,
    onCopyCrashLog: () -> Unit,
) {
    SettingsPageList(title = SettingsPage.Logs.title, state = rememberLazyListState()) {
        item(key = "crash") {
            SettingsSection(
                title = "崩溃日志",
                note = "日志写在应用私有目录，未 root 的设备无法用文件管理器打开，所以提供复制到剪贴板。",
            ) {
                if (hasCrashLog) {
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = "复制上次崩溃日志",
                        summary = "把崩溃时的调用栈放进剪贴板",
                        onClick = onCopyCrashLog,
                    )
                } else {
                    InfoPreference(
                        title = "暂无崩溃记录",
                        summary = "最近一次运行没有未捕获的异常",
                    )
                }
            }
        }
        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

/**
 * 音效: the platform equaliser, ported from CurrentMusicX.
 *
 * Deliberately not a "sound effects" catalogue: that product ships NetEase's cloud preset packages and
 * their proprietary engine, which cannot be copied into this app. What a device can actually do offline
 * is a system `Equalizer` on our own audio session, and that is what this page exposes — on/off, the six
 * presets, and the five bands with the same millibel grid and interpolation the ported controller uses.
 */
@Composable
private fun EffectsPage(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    preset: String,
    onPresetChange: (String) -> Unit,
    bands: List<Int>,
    onBandChange: (Int, Int) -> Unit,
) {
    // A drag writes to the prefs once, on release — not on every frame.
    var draft by remember(bands) { mutableStateOf(bands) }

    SettingsPageList(title = SettingsPage.Effects.title, state = rememberLazyListState()) {
        item(key = "equalizer") {
            SettingsSection(
                title = "均衡器",
                note = "使用系统 Equalizer，只作用于云音自己的音频会话；设备不支持时会自动降级为关闭。",
            ) {
                SwitchPreference(
                    insideMargin = RowInsets,
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    title = "启用均衡器",
                    summary = "系统级 EQ，仅影响本应用的播放",
                )
                EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.forEach { option ->
                    GroupDivider()
                    ArrowPreference(
                        insideMargin = RowInsets,
                        title = option.label,
                        enabled = enabled,
                        endActions = {
                            if (preset == option.name) PreferenceValue("✓")
                        },
                        onClick = { onPresetChange(option.name) },
                    )
                }
            }
        }

        if (enabled) {
            item(key = "bands") {
                SettingsSection(
                    title = "频段",
                    note = "±12 dB，拖到 0 即为平直。手动调整后会切到「自定义」。",
                ) {
                    EqualizerSettings.FREQUENCIES.forEachIndexed { index, hz ->
                        if (index > 0) GroupDivider()
                        val gain = draft.getOrElse(index) { 0 }
                        SliderPreference(
                            insideMargin = RowInsets,
                            value = gain.toFloat(),
                            onValueChange = { raw ->
                                draft = draft.toMutableList().also { it[index] = raw.roundToInt() }
                            },
                            onValueChangeFinished = {
                                onBandChange(index, draft.getOrElse(index) { gain })
                            },
                            title = "${hz} Hz",
                            endActions = { PreferenceValue(formatGain(gain)) },
                            valueRange = -1200f..1200f,
                            steps = 23,
                        )
                    }
                }
            }
        }

        item(key = "bottomSpacer") { Spacer(Modifier.height(BottomSpacer)) }
    }
}

/** Millibels as a signed dB label, the unit the platform effect actually stores. */
private fun formatGain(millibel: Int): String {
    val db = millibel / 100.0
    return if (db > 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
}

// ---------------------------------------------------------------------------------------------
// Page scaffolding
// ---------------------------------------------------------------------------------------------
/**
 * System-back handling for subpages: "up one level" while a subpage is open, otherwise nothing.
 *
 * Deliberately not `BackHandler`. The dispatcher runs callbacks **newest first**, and the host's own
 * "close Settings" handler is composed *after* this screen (it sits further down `MainActivity`), so a
 * `BackHandler` registered here would be older and would lose — the gesture would slam the whole screen
 * shut from a subpage instead of stepping back to the list.
 *
 * Re-registering the callback whenever [enabled] flips puts it at the end of the dispatcher's list at
 * exactly the moment it becomes relevant, which is what makes it win. It is removed again on dispose,
 * so nothing leaks while the screen is closed.
 */
@Composable
private fun SubpageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcherOwner = LocalOnBackPressedDispatcherOwner.current ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(dispatcherOwner, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = currentOnBack()
        }
        dispatcherOwner.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/** Bottom breathing room under the last card, shared by every page. */
private val BottomSpacer = 28.dp

// ---------------------------------------------------------------------------------------------
// Shape and spacing — docs/SETTINGS-STYLE.md §4
// ---------------------------------------------------------------------------------------------

/** Card inset from the screen edge. */
private val CardInset = 16.dp

/** Flat, borderless card: 14 dp holds the list together without drawing an outline around it. */
private val CardCorner = 14.dp

/**
 * Row padding prescribed by the style guide: 18 start / 14 end / 10 top / 10 bottom.
 *
 * The 14 dp end inset is what puts a switch or chevron exactly 14 dp from the card edge; the 10 dp
 * vertical padding is what lets rows breathe instead of being held apart by stacked dividers.
 */
private val RowInsets = PaddingValues(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)

/** Where row text starts inside the card; dividers and notes align to the same line. */
private val RowStartInset = 18.dp

/** The page's single text column: card inset + row inset, so headers, rows and notes line up. */
private val TextColumn = CardInset + RowStartInset

/**
 * The scroll container every page shares: a large in-content title that scrolls under the frosted
 * strip, then the page's sections.
 *
 * `contentPadding` carries the strip's height rather than a `padding` on the list, so the first card
 * starts below the blur and then scrolls *into* it — the same arrangement the rest of the app uses.
 */
@Composable
private fun SettingsPageList(
    title: String,
    state: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(top = glassTopBarTopPadding(), bottom = BottomSpacer),
    ) {
        item(key = "pageTitle") {
            Text(
                text = title,
                modifier = Modifier.padding(start = TextColumn, top = 4.dp, bottom = 4.dp),
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                // 32 sp Bold, −0.6 tracking: large sizes need negative tracking or the glyphs drift
                // apart at CJK spacing.
                fontSize = 32.sp,
                letterSpacing = (-0.6).sp,
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
        content()
    }
}

/**
 * One section: header, exactly one [Card], nothing else.
 *
 * **No shadow, no stroke.** The card is layered by background tone alone (page `background` under a
 * card of `secondaryBackground`), which is what keeps the page quiet: an outlined or elevated card
 * would draw the eye to the frame instead of the setting inside it.
 *
 * The note ([note]) is the last row *inside* the card — muted, 12.5 sp, after a hairline. It used to be
 * a second card of its own, which read as a separate group of settings and doubled the vertical rhythm
 * of the page for one sentence of explanation.
 */
@Composable
private fun SettingsSection(
    title: String,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CardInset),
            cornerRadius = CardCorner,
        ) {
            content()
            if (!note.isNullOrBlank()) {
                GroupDivider()
                Text(
                    text = note,
                    modifier = Modifier.padding(
                        start = RowStartInset,
                        end = 14.dp,
                        top = 10.dp,
                        bottom = 12.dp,
                    ),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * The section label: 13 sp, SemiBold, +0.4 sp tracking, muted.
 *
 * It shares the muted ink with the row subtitles on purpose — the hierarchy between "分区标题" and
 * "副文字" is carried by weight and tracking, not by a second grey, so the page has one quiet voice
 * instead of two competing ones. 28 dp above and 7 dp below per the style guide: sections are held
 * apart by air, not by rules.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = TextColumn, end = TextColumn, top = 28.dp, bottom = 7.dp),
        fontFamily = SFPro,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * Hairline between two rows, inset to the row text and drawn in `separator @ 35%`.
 *
 * Only ever placed *before* a row, never after the last one — the card's own bottom edge is the end of
 * the group. That is also why conditional rows each start with a divider.
 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = RowStartInset))
}

/** The right-hand value of a preference: 14 sp, one line, muted, never wider than its share of the row. */
@Composable
private fun RowScope.PreferenceValue(
    text: String,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Text(
        text = text,
        modifier = Modifier.align(Alignment.CenterVertically),
        fontSize = 14.sp,
        letterSpacing = 0.sp,
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
 * A short value (a version, a state) sits on the right as [value]; anything that is a sentence goes
 * through [summary] instead, because the right-hand slot is capped at a fraction of the row's width and
 * a one-line ellipsis would cut a diagnostic off exactly where it starts being useful.
 */
@Composable
private fun InfoPreference(
    title: String,
    value: String? = null,
    summary: String? = null,
) {
    val valueSlot: (@Composable RowScope.() -> Unit)? = if (value != null) {
        { PreferenceValue(value) }
    } else {
        null
    }
    BasicComponent(
        insideMargin = RowInsets,
        title = title,
        summary = summary,
        endActions = valueSlot,
    )
}

/**
 * A row that opens something outside the app.
 *
 * Same component as every other arrow row on purpose — the destination being another application does
 * not deserve a different shape — but the value slot names where the tap lands, which is the one thing
 * an in-app chevron cannot say.
 */
@Composable
private fun ExternalPreference(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    val valueSlot: @Composable RowScope.() -> Unit = {
        if (value != null) PreferenceValue(value)
    }
    ArrowPreference(
        insideMargin = RowInsets,
        title = title,
        summary = summary,
        endActions = valueSlot,
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
        // Full label ink, not the accent: accent is reserved for on-state switches, destructive actions
        // and links, and a "connected" readout is none of those. Contrast is what carries the emphasis.
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val retryable = enabled && available && !connected

    // Only when there is something to retry does the row become an arrow (a tap that acts). Otherwise
    // it is a report, and a chevron on a report is the "nothing happens when I tap" bug in advance.
    if (retryable) {
        ArrowPreference(
            insideMargin = RowInsets,
            title = "连接状态",
            summary = hint,
            endActions = { PreferenceValue(status, color = statusColor) },
            onClick = onRetry,
        )
    } else {
        BasicComponent(
            insideMargin = RowInsets,
            title = "连接状态",
            summary = hint,
            endActions = { PreferenceValue(status, color = statusColor) },
        )
    }
}

/**
 * What the "检查更新" row shows on its right-hand side.
 *
 * Each state gets its own wording because they are genuinely different situations, and collapsing them
 * is how an updater ends up telling someone "已是最新" when it never managed to ask. A failed check says
 * so, and stays tappable so the user can retry.
 */
internal fun updateRowValue(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Idle -> "点击检查"
    is UpdateUiState.Available -> "发现 ${state.update.versionName}"
    is UpdateUiState.AvailableSkipped -> "有新版本 ${state.update.versionName}"
    is UpdateUiState.Downloading -> if (state.progress in 0..100) "下载中 ${state.progress}%" else "下载中…"
    is UpdateUiState.Failed -> "检查失败"
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
        insideMargin = RowInsets,
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
                        minWidth = 44.dp,
                        minHeight = 44.dp,
                        // No fill: these are text actions at the foot of a row, not buttons competing
                        // with the row itself (docs/SETTINGS-STYLE.md §5).
                        colors = ButtonDefaults.textButtonColors(
                            color = Color.Transparent,
                            textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        insideMargin = PaddingValues(horizontal = 12.dp),
                    )
                    // 8 dp between two adjacent touch targets: below that the delete button starts
                    // collecting mis-taps meant for its neighbour.
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    minWidth = 44.dp,
                    minHeight = 44.dp,
                    // Danger: accent text, no fill. The app's accent is already a red, so "danger" reads
                    // as danger without introducing a second semantic colour into the page.
                    colors = ButtonDefaults.textButtonColors(
                        color = Color.Transparent,
                        textColor = MiuixTheme.colorScheme.primary,
                    ),
                    insideMargin = PaddingValues(horizontal = 12.dp),
                )
            }
        },
    )
}

/**
 * Maps the app's [AppPalette] onto the miuix color scheme.
 *
 * The palette is the single source of truth for colour in this app (it carries the artwork accent
 * override), so the miuix roles are *derived* from it rather than restated: the accent becomes
 * `primary` — which is what a miuix switch track, slider and button fill use — the label scale becomes
 * the `on*` roles, and the grouped backgrounds become `surfaceContainer`, the colour a [Card] paints.
 * Whatever the palette does not distinguish (error containers, key-point dots) keeps the scheme's own
 * value, taken from the light or dark base by [AppPalette.isDark].
 *
 * `onSurfaceVariantSummary` is the one role that carries a design decision rather than a mapping: it is
 * the palette's **secondary label**, which is what every row subtitle and every section note reads.
 * Making it a theme role means a quiet subtitle is the default instead of something each row has to
 * remember to opt into.
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
        // Subtitles and section notes: the muted role.
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
        // `separator @ 35%`: a hairline that separates without drawing a table. Absolute alpha, so it
        // reads the same whether the palette's separator already carries opacity or not.
        dividerLine = palette.separator.copy(alpha = 0.35f),
        windowDimming = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.3f),
        sliderKeyPoint = accent.copy(alpha = 0.3f),
        sliderKeyPointForeground = accent,
        sliderBackground = palette.label.copy(alpha = if (palette.isDark) 0.15f else 0.06f),
    )
}
