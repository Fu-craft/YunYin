// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import com.yunyin.music.ui.theme.Motion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunyin.music.ui.AppViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.lxsource.LxImportedSource
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.update.UpdateUiState
import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.background.BackgroundStyle
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

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
    /** Whether tracks run into each other without the dead air that makes them sound like files. */
    seamlessTransition: Boolean = true,
    onSeamlessTransitionChange: (Boolean) -> Unit = {},
    /** Overlap length in seconds for the dual-player crossfade. */
    crossfadeSeconds: Int = 6,
    onCycleCrossfadeSeconds: () -> Unit = {},
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
    // The frosted navigation strip: the recorded page layer the bar samples, with the bar itself
    // composed outside it (after the scroll column closes, below).
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            // The screen is a full-window overlay and is not wrapped in a Surface by its host, so it
            // has to paint its own page background; the recorded layer above paints the same colour
            // *into the blur field* (that fill is what the top strip's blur samples).
            .background(AppTheme.palette.background),
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .navigationBarsPadding()
                    .padding(top = glassTopBarTopPadding())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
            // Large in-content title: it scrolls under the frosted strip; the bar stays title-less.
            Text(
                text = "设置",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = AppTheme.palette.label,
            )
            Spacer(Modifier.height(20.dp))

            SettingsSection(title = "播放", first = true) {
                ToggleRow("无缝衔接", "切歌时让两首重叠淡入淡出", seamlessTransition, onSeamlessTransitionChange)
                RowDivider()
                LinkRow("交叉淡化时长", "${crossfadeSeconds} 秒", onCycleCrossfadeSeconds, "切歌重叠的时长")
                RowDivider()
                LinkRow("定时关闭", sleepTimerLabel, onShowSleepTimer, "到点后自动停止播放")
                RowDivider()
                LinkRow("播放器界面", playerStyle.label, onShowPlayerStyle, "播放页展示封面或唱片")
                RowDivider()
                ToggleRow("自动进入沉浸歌词", "歌词页闲置几秒后隐藏播放控件", autoImmersiveLyrics, onAutoImmersiveLyricsChange)
            }

            SettingsSection(title = "正在播放外观") {
                ToggleRow("模糊封面背景", "用当前封面作为播放页背景", nowPlayingCoverBlurBackground, onNowPlayingCoverBlurBackgroundChange)
                RowDivider()
                ToggleRow("音频律动", "背景随音频节奏变化", nowPlayingAudioReactive, onNowPlayingAudioReactiveChange)
                RowDivider()
                ToggleRow("动态背景", "显示随时间变化的动态背景", nowPlayingDynamicBackground, onNowPlayingDynamicBackgroundChange)
                if (animatedCoverAvailable) {
                    RowDivider()
                    ToggleRow(
                        "动态封面",
                        "为当前歌曲显示服务端动态封面",
                        animatedCoverEnabled,
                        onAnimatedCoverChange,
                    )
                }
                RowDivider()
                LinkRow(
                    "背景样式",
                    nowPlayingBackgroundStyle.label,
                    onShowBackgroundStyle,
                    "选择正在播放页的动态背景",
                )
            }

            SettingsSection(
                title = "状态栏歌词",
                footnote = "词幕需已安装并在系统中启用；Flyme 歌词只在对应系统上可用。",
            ) {
                ToggleRow("推送到词幕", "由「词幕」显示歌词", lyriconEnabled, onLyriconEnabledChange)
                RowDivider()
                LyriconRow(
                    enabled = lyriconEnabled,
                    connected = lyriconConnected,
                    available = lyriconAvailable,
                    onRetry = onRetryLyricon,
                )
                RowDivider()
                FlymeTickerRow(
                    supported = flymeSupported,
                    enabled = statusBarLyrics,
                    onChange = onStatusBarLyricsChange,
                )
                RowDivider()
                ActionRow("测试状态栏歌词", onTestFlymeLyric, "发送一条测试歌词到状态栏")
            }

            SettingsSection(title = "音源") {
                LxSourcesSection(
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
            }

            SettingsSection(
                title = "下载与存储",
                footnote = "清除歌词缓存不会删除本地音乐或已下载的歌曲。",
            ) {
                LinkRow("下载中心", "", onOpenDownloads, "查看进行中、已完成与失败的下载")
                RowDivider()
                ToggleRow("仅 Wi-Fi 下载", "避免用移动数据开始下载", downloadWifiOnly, onDownloadWifiOnlyChange)
                RowDivider()
                ActionRow("清除歌词缓存", onClearLyricsCache, "删除已下载的歌词文件")
            }

            SettingsSection(title = "关于") {
                InfoRow("版本", com.yunyin.music.BuildConfig.VERSION_NAME)
                if (updateConfigured) {
                    RowDivider()
                    LinkRow("检查更新", updateRowValue(updateState), onCheckUpdate)
                }
                RowDivider()
                InfoRow("歌词来源", "AMLL 逐字歌词")
                RowDivider()
                LicenseRow("云音（本项目）", "AGPL-3.0-only", "$PROJECT_URL/blob/master/LICENSE")
                RowDivider()
                LinkRow("项目主页", "GitHub", onOpenProject)
                if (hasCrashLog) {
                    RowDivider()
                    ActionRow("复制上次崩溃日志", onCopyCrashLog)
                }
            }
            Spacer(Modifier.height(12.dp))

            SettingsSection(
                title = "开源组件",
                footnote = "各组件许可证与上游地址；完整声明见仓库 NOTICE。",
            ) {
                LicenseRow("AMLL / Mesh Gradient", "AGPL-3.0-only", "https://github.com/amll-dev/applemusic-like-lyrics")
                RowDivider()
                LicenseRow("QPlayer", "Apache-2.0", "https://github.com/TIMER-err/QPlayer")
                RowDivider()
                LicenseRow("NeriPlayer", "GPL-3.0", "https://github.com/cwuom/NeriPlayer")
                RowDivider()
                LicenseRow("accompanist lyrics-core", "Apache-2.0", "https://github.com/6xingyv/Accompanist-Lyrics")
                RowDivider()
                LicenseRow("accompanist lyrics-ui（vendored）", "Apache-2.0", "https://github.com/6xingyv/Accompanist-Lyrics")
                RowDivider()
                LicenseRow("io.github.kyant0:backdrop", "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass")
                RowDivider()
                LicenseRow("androidx.media3", "Apache-2.0", "https://github.com/androidx/media")
                RowDivider()
                LicenseRow("OkHttp", "Apache-2.0", "https://github.com/square/okhttp")
                RowDivider()
                LicenseRow("Coil", "Apache-2.0", "https://github.com/coil-kt/coil")
                RowDivider()
                LicenseRow("QuickJS / quickjs-kt", "Apache-2.0", "https://github.com/dokar3/quickjs-kt")
                RowDivider()
                LicenseRow("Material Symbols", "Apache-2.0", "https://github.com/google/material-design-icons")
                RowDivider()
                LicenseRow("Lucide（FolderMusic 图标）", "ISC", "https://github.com/lucide-icons/lucide")
            }
            Spacer(Modifier.height(24.dp))
                }
            }
        GlassTopBar(
            backdrop = topBarBackdrop,
            modifier = Modifier.align(Alignment.TopCenter),
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
                        tint = AppTheme.palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
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
private fun LyriconRow(
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
    val retryable = enabled && available && !connected
    SettingsRow(
        title = "连接状态",
        subtitle = hint,
        onClick = if (retryable) onRetry else null,
        trailing = {
            Text(
                status,
                fontFamily = SFPro,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (connected) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
            )
        },
    )
}

/** Flyme 状态栏歌词: only interactive on a ROM that ported the feature. */
@Composable
private fun FlymeTickerRow(
    supported: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = "Flyme 状态栏歌词",
        subtitle = if (supported) "由系统通知栏显示，需保持常驻通知" else "当前系统未内置该功能",
        onClick = if (supported) ({ onChange(!enabled) }) else null,
        trailing = {
            if (supported) {
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                        checkedTrackColor = AppTheme.palette.accent,
                    ),
                )
            }
        },
    )
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

/** A card-less group: header, rows directly on the page background, optional footnote. */
@Composable
private fun SettingsSection(
    title: String,
    footnote: String? = null,
    first: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.padding(top = 28.dp)),
    ) {
        Text(
            title,
            fontFamily = SFPro,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth(), content = content)
        if (footnote != null) {
            Text(
                footnote,
                fontFamily = SFPro,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** One settings row: title/subtitle left, trailing value/control right. No fill, no icon tile. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
) {
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, androidx.compose.animation.core.tween(Motion.ItemAppearDuration, easing = Motion.EaseOut))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) 52.dp else 64.dp)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 6f
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        indication = com.yunyin.music.ui.components.rememberControlRipple(bounded = true),
                        interactionSource = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = if (destructive) AppTheme.palette.accent else AppTheme.palette.label,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                SfIcons.ChevronRight,
                contentDescription = null,
                tint = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.size(12.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Hairline between rows, inset to the title. Only placed between rows, never after the last. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(AppTheme.palette.separator.copy(alpha = 0.45f)),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!enabled) },
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = AppTheme.palette.accent,
                ),
            )
        },
    )
}

@Composable
private fun LicenseRow(name: String, license: String, url: String) {
    val uriHandler = LocalUriHandler.current
    LinkRow(name, license, { runCatching { uriHandler.openUri(url) } })
}

@Composable
private fun LinkRow(
    text: String,
    value: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    SettingsRow(
        title = text,
        subtitle = subtitle,
        value = value.ifBlank { null },
        onClick = onClick,
        showChevron = true,
    )
}

@Composable
private fun InfoRow(text: String, value: String) {
    SettingsRow(title = text, value = value)
}

@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
) {
    SettingsRow(title = text, subtitle = subtitle, onClick = onClick, destructive = destructive)
}

/**
 * The 音源 section of Settings: import a script, then manage what was imported.
 *
 * ## Why the list is inline rather than a sheet
 *
 * The set is small (a handful of sources), and the whole point of the section is to answer "what is installed
 * and which one is on". A sheet would hide that behind a tap; three rows say it at a glance, in the same
 * visual language as every other settings row.
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
private fun LxSourcesSection(
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
    LinkRow("导入自定义音源", if (importing) "校验中…" else "", { if (!importing) onImport() }, "选择本地 .js 脚本并校验")
    RowDivider()
    LinkRow("从链接导入", if (importing) "校验中…" else "", { if (!importing) onImportFromUrl() }, "粘贴网络音源地址")
    if (importing) {
        RowDivider()
        HintRow("正在运行脚本并等待它发送 inited 事件…")
    }
    if (message != null) {
        RowDivider()
        HintRow(message)
    }
    if (errorLogAvailable) {
        RowDivider()
        ActionRow("复制音源错误日志", onCopyErrorLog, "包含脚本失败时的详细调用栈")
    }
    if (sources.isEmpty()) {
        RowDivider()
        if (!importing) {
            HintRow("尚未导入音源脚本。支持 LX Music（洛雪音乐）自定义源，导入时会校验脚本能否初始化。")
        }
        return
    }
    sources.forEach { source ->
        RowDivider()
        LxSourceRow(
            source = source,
            onToggle = { enabled -> onToggle(source.id, enabled) },
            onDelete = { onDelete(source.id) },
            onRefresh = if (source.originUrl != null) ({ onRefresh(source.id) }) else null,
        )
    }
    if (sources.size > 1) {
        RowDivider()
        HintRow("同一个音源标识（如 wy）只能启用一个脚本，避免同一个平台由两个脚本同时应答。")
    }
    if (sources.any { it.enabled && LxSourceKey.WY in it.keys }) {
        RowDivider()
        HintRow("已启用的音源会优先解析网易云播放链接；解析失败或超时会自动回退到内置接口。")
    }
}

/** One imported source: name and its declaration on the left, delete and the enable switch on the right. */
@Composable
private fun LxSourceRow(
    source: LxImportedSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** Present only for a URL-sourced script, whose origin can be re-fetched. */
    onRefresh: (() -> Unit)? = null,
) {
    val subtitle = buildString {
        append("支持 ").append(source.keysLabel)
        source.versionAuthorLabel.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(source.header.name, fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
                Text(
                    subtitle,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.originUrl?.let { origin ->
                    Text(
                        origin,
                        fontFamily = SFPro,
                        fontSize = 11.sp,
                        color = AppTheme.palette.tertiaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = AppTheme.palette.accent,
                ),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (onRefresh != null) {
                InlineAction("更新", onRefresh)
                Spacer(Modifier.width(6.dp))
            }
            InlineAction("删除", onDelete)
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** A 44 dp text action used inside a source row's trailing controls. */
@Composable
private fun InlineAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent)
    }
}

/** A one-line explanation under the 音源 rows: status, why a source is off, or the collision rule. */
@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = AppTheme.palette.tertiaryLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

