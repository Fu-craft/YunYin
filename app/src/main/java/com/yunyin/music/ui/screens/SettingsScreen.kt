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
            // The page's own large title. The strip is icon-only now (no repeated bar title to
            // fight the blur for legibility), so this is the page's only "设置".
            Text(
                text = "设置",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = AppTheme.palette.label,
            )
            Spacer(Modifier.height(16.dp))

            // Order is by expected frequency: playback first (the reason people open settings),
            // then the now-playing look, status-bar lyrics (niche but discoverable), sources,
            // downloads/storage, and About last.
            SettingsSection(title = "播放", subtitle = "切歌、播放页与定时行为") {
                ToggleRow(
                    title = "无缝衔接",
                    subtitle = "切歌时让两首重叠淡入淡出",
                    enabled = seamlessTransition,
                    onChange = onSeamlessTransitionChange,
                )
                RowDivider()
                LinkRow(
                    text = "交叉淡化时长",
                    subtitle = "切歌重叠的时长",
                    value = "${crossfadeSeconds} 秒",
                    onClick = onCycleCrossfadeSeconds,
                )
                RowDivider()
                LinkRow(
                    text = "定时关闭",
                    subtitle = "到点后自动停止播放",
                    value = sleepTimerLabel,
                    onClick = onShowSleepTimer,
                )
                RowDivider()
                LinkRow(
                    text = "播放器界面",
                    subtitle = "播放页展示封面或唱片",
                    value = playerStyle.label,
                    onClick = onShowPlayerStyle,
                )
                RowDivider()
                ToggleRow(
                    title = "自动进入沉浸歌词",
                    subtitle = "歌词页闲置几秒后隐藏播放控件",
                    enabled = autoImmersiveLyrics,
                    onChange = onAutoImmersiveLyricsChange,
                )
            }

            SettingsSection(title = "正在播放外观", subtitle = "播放页的背景与律动") {
                ToggleRow(
                    title = "模糊封面背景",
                    subtitle = "用当前封面作为播放页背景",
                    enabled = nowPlayingCoverBlurBackground,
                    onChange = onNowPlayingCoverBlurBackgroundChange,
                )
                RowDivider()
                ToggleRow(
                    title = "音频律动",
                    subtitle = "背景随音频节奏变化",
                    enabled = nowPlayingAudioReactive,
                    onChange = onNowPlayingAudioReactiveChange,
                )
                RowDivider()
                ToggleRow(
                    title = "动态背景",
                    subtitle = "显示随时间变化的动态背景",
                    enabled = nowPlayingDynamicBackground,
                    onChange = onNowPlayingDynamicBackgroundChange,
                )
            }

            SettingsSection(title = "状态栏歌词", subtitle = "在系统状态栏或锁屏显示歌词") {
                ToggleRow(
                    title = "推送到词幕",
                    subtitle = "由「词幕」显示歌词，需已安装该应用",
                    enabled = lyriconEnabled,
                    onChange = onLyriconEnabledChange,
                )
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
                ActionRow(
                    text = "测试状态栏歌词",
                    subtitle = "发送一条测试歌词到状态栏",
                    onClick = onTestFlymeLyric,
                )
            }

            SettingsSection(title = "音源", subtitle = "自定义音源脚本与错误日志") {
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

            SettingsSection(title = "下载与存储") {
                LinkRow(
                    text = "下载中心",
                    subtitle = "查看进行中、已完成与失败的下载",
                    value = "",
                    onClick = onOpenDownloads,
                )
                RowDivider()
                ToggleRow(
                    title = "仅 Wi-Fi 下载",
                    subtitle = "避免用移动数据开始下载",
                    enabled = downloadWifiOnly,
                    onChange = onDownloadWifiOnlyChange,
                )
                RowDivider()
                ActionRow(
                    text = "清除歌词缓存",
                    subtitle = "删除已下载的歌词文件",
                    onClick = onClearLyricsCache,
                )
            }

            SettingsSection(title = "关于") {
                InfoRow(text = "版本", value = com.yunyin.music.BuildConfig.VERSION_NAME)
                if (updateConfigured) {
                    RowDivider()
                    LinkRow(
                        text = "检查更新",
                        value = updateRowValue(updateState),
                        onClick = onCheckUpdate,
                    )
                }
                RowDivider()
                InfoRow(text = "歌词来源", value = "AMLL 逐字歌词")
                RowDivider()
                LinkRow(text = "项目主页", value = "GitHub", onClick = onOpenProject)
                if (hasCrashLog) {
                    RowDivider()
                    ActionRow(text = "复制上次崩溃日志", onClick = onCopyCrashLog)
                }
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (retryable) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "连接状态",
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = hint,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        Text(
            text = status,
            fontFamily = SFPro,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (connected) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
        )
    }
}

/**
 * Flyme 状态栏歌词 toggle.
 *
 * Only interactive on a ROM that ported the feature (detected by reflection on the two private
 * notification flags Flyme requires). Elsewhere it states that plainly instead of offering a switch
 * that would do nothing — the feature is implemented by the ROM's ticker, so there is nothing the app
 * can substitute.
 *
 * The **row** owns the tap and the switch is presentational (`onCheckedChange = null`). Giving both a
 * handler means the switch and the row both act on one tap, and which of the two wins depends on hit
 * testing — so the toggle could fire twice or be swallowed. One target, one handler; the whole row is
 * the target, which is also the larger and more forgiving one.
 */
@Composable
private fun FlymeTickerRow(
    supported: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (supported) {
                    Modifier.clickable { onChange(!enabled) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Flyme 状态栏歌词",
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = if (supported) {
                    "由系统通知栏显示，需保持常驻通知"
                } else {
                    "当前系统未内置该功能"
                },
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        if (supported) {
            Switch(
                checked = enabled,
                // Null so the row above is the only handler; see the note on this function.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = AppTheme.palette.accent,
                ),
            )
        }
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

/** A titled card of settings rows. One card per group; one row component per control type. */
@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(
            title,
            fontFamily = SFPro,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontFamily = SFPro,
                fontSize = 11.sp,
                color = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.card))
                .background(AppTheme.palette.secondaryBackground),
            content = content,
        )
    }
}

/** Hairline between rows inside a card; inset to the row's text, not the card edge. */
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

/** A title/subtitle row with a switch. The whole row is the tap target. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = AppTheme.palette.accent,
            ),
        )
    }
}

/** A navigation/value row: title + optional subtitle on the left, value + chevron on the right. */
@Composable
private fun LinkRow(
    text: String,
    value: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) 52.dp else 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value.isNotBlank()) {
            Text(value, fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.secondaryLabel)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            SfIcons.ChevronRight,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** Non-interactive title/value row (version, lyric source). */
@Composable
private fun InfoRow(text: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label, modifier = Modifier.weight(1f))
        Text(value, fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.secondaryLabel)
    }
}

/** A neutral (or destructive) action row. */
@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = if (destructive) AppTheme.palette.accent else AppTheme.palette.label,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!destructive) {
            Icon(
                SfIcons.ChevronRight,
                contentDescription = null,
                tint = AppTheme.palette.tertiaryLabel,
                modifier = Modifier.size(15.dp),
            )
        }
    }
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
    LinkRow(
        text = "导入自定义音源",
        subtitle = "选择本地 .js 脚本并校验",
        value = if (importing) "校验中…" else "",
        onClick = { if (!importing) onImport() },
    )
    RowDivider()
    LinkRow(
        text = "从链接导入",
        subtitle = "粘贴网络音源地址",
        value = if (importing) "校验中…" else "",
        onClick = { if (!importing) onImportFromUrl() },
    )
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
        ActionRow(text = "复制音源错误日志", subtitle = "包含脚本失败时的详细调用栈", onClick = onCopyErrorLog)
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
                Text(source.header.name, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label)
                Text(
                    subtitle,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
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
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = AppTheme.palette.tertiaryLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

