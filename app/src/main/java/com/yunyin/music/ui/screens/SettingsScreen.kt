package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    /** Whether the lyrics page auto-hides the chrome after the idle delay (default off). */
    autoImmersiveLyrics: Boolean = false,
    onAutoImmersiveLyricsChange: (Boolean) -> Unit = {},
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
            Spacer(Modifier.height(14.dp))
            SectionLabel("状态栏歌词")
            // The enable switch leads, because it decides whether the two rows below have anything to
            // say: with 词幕 publishing off there is no connection to report and no status to retry.
            ToggleRow(
                title = "词幕状态栏歌词",
                subtitle = "把当前歌词推送给「词幕」，由它显示在状态栏／锁屏",
                enabled = lyriconEnabled,
                onChange = onLyriconEnabledChange,
            )
            LyriconRow(
                enabled = lyriconEnabled,
                connected = lyriconConnected,
                available = lyriconAvailable,
                onRetry = onRetryLyricon,
            )
            FlymeTickerRow(
                supported = flymeSupported,
                enabled = statusBarLyrics,
                onChange = onStatusBarLyricsChange,
            )

            Spacer(Modifier.height(28.dp))

            SectionLabel("播放")
            ToggleRow(
                title = "无缝衔接",
                subtitle = "柔化每首歌的开头，切歌不会突然出声",
                enabled = seamlessTransition,
                onChange = onSeamlessTransitionChange,
            )
            ToggleRow(
                title = "自动进入沉浸歌词",
                subtitle = "歌词页闲置几秒后自动隐藏播放控件；默认开启",
                enabled = autoImmersiveLyrics,
                onChange = onAutoImmersiveLyricsChange,
            )
            LinkRow(
                text = "定时关闭",
                value = sleepTimerLabel,
                onClick = onShowSleepTimer,
            )
            // The player's centrepiece: a rounded cover, or a rotating record. A row that opens a picker
            // rather than an inline switch, because there are two choices and each needs a line saying what
            // it is — the same reason the quality tier uses a sheet.
            LinkRow(
                text = "播放器界面",
                value = playerStyle.label,
                onClick = onShowPlayerStyle,
            )

            Spacer(Modifier.height(28.dp))

            SectionLabel("音源")
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

            Spacer(Modifier.height(28.dp))

            SectionLabel("存储")
            ActionRow(text = "清除歌词缓存", onClick = onClearLyricsCache)

            Spacer(Modifier.height(28.dp))

            SectionLabel("关于")
            // Read from BuildConfig rather than a literal: the previous hardcoded "1.0.0" was a
            // second source of truth for the version and drifted from the manifest the moment the
            // version was bumped.
            InfoRow(text = "版本", value = com.yunyin.music.BuildConfig.VERSION_NAME)
            // Placed immediately under the version: "检查更新" only makes sense next to the number it
            // compares against. Hidden when the build has no update repository, so the row cannot offer
            // something that would immediately fail.
            if (updateConfigured) {
                LinkRow(
                    text = "检查更新",
                    value = updateRowValue(updateState),
                    onClick = onCheckUpdate,
                )
            }
            InfoRow(text = "歌词", value = "AMLL 逐字歌词")
            LinkRow(text = "项目主页", value = "GitHub", onClick = onOpenProject)
            // Only shown when there *is* a recorded crash. A permanently visible row would be noise for
            // everyone who never crashes, and its presence is itself the useful signal; but without it a
            // user who hits a crash has no way to hand over the report — which is how the launch crash
            // in 3.8.0 had to be reproduced from a screenshot of the stack trace instead of just read.
            if (hasCrashLog) {
                ActionRow(text = "复制上次崩溃日志", onClick = onCopyCrashLog)
            }
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
            .clip(ContinuousRoundedRectangle(10.dp))
            .then(if (retryable) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
            .clip(ContinuousRoundedRectangle(10.dp))
            .then(
                if (supported) {
                    Modifier.clickable { onChange(!enabled) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.palette.secondaryLabel,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * A title/subtitle row with a switch.
 *
 * The whole row is the tap target and the switch takes a null handler, the same arrangement as
 * [FlymeTickerRow] and for the same reason: two handlers on one tap fire twice or get swallowed,
 * depending on hit testing.
 */
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
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable { onChange(!enabled) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = subtitle,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
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

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoRow(text: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}

/**
 * An [InfoRow] that opens something.
 *
 * The value is tinted with the accent and followed by an outbound glyph, which is how a link reads in
 * a settings list: the colour says it acts, the arrow says the act leaves the app. Without either, a
 * tappable row is indistinguishable from the two informational rows above it.
 */
@Composable
private fun LinkRow(text: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.accent,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            SfIcons.ArrowUpRight,
            contentDescription = null,
            tint = AppTheme.palette.accent,
            modifier = Modifier.size(13.dp),
        )
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
        value = if (importing) "校验中…" else "选择 .js 文件",
        // A second tap while the first script is running would start a second engine; the row says what it is
        // doing instead of silently ignoring the tap.
        onClick = { if (!importing) onImport() },
    )
    // URL import goes through exactly the same validation pipeline after the download; the row is separate
    // because the user is choosing a *delivery method*, not a different kind of script.
    LinkRow(
        text = "从链接导入",
        value = if (importing) "校验中…" else "粘贴网络音源地址",
        onClick = { if (!importing) onImportFromUrl() },
    )
    if (importing) {
        HintRow("正在运行脚本并等待它发送 inited 事件…")
    }
    if (message != null) {
        HintRow(message)
    }
    // Only when a failure was recorded: the detail is a JS stack, and copying it is how an author gets a report
    // they can act on. A permanently visible row would be noise for everyone whose sources work.
    if (errorLogAvailable) {
        ActionRow(text = "复制音源错误日志", onClick = onCopyErrorLog)
    }
    if (sources.isEmpty()) {
        if (!importing) {
            HintRow("尚未导入音源脚本。支持 LX Music（洛雪音乐）自定义源，导入时会校验脚本能否初始化。")
        }
        return
    }
    sources.forEach { source ->
        LxSourceRow(
            source = source,
            onToggle = { enabled -> onToggle(source.id, enabled) },
            onDelete = { onDelete(source.id) },
            onRefresh = if (source.originUrl != null) ({ onRefresh(source.id) }) else null,
        )
    }
    if (sources.size > 1) {
        HintRow("同一个音源标识（如 wy）只能启用一个脚本，避免同一个平台由两个脚本同时应答。")
    }
    if (sources.any { it.enabled && LxSourceKey.WY in it.keys }) {
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = source.header.name,
                fontFamily = SFPro,
                fontSize = 16.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = subtitle,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            // The origin is part of the source's identity: a URL script is updatable, and the user needs to know
            // where it came from before reloading or deleting it.
            source.originUrl?.let { origin ->
                Text(
                    text = origin,
                    fontFamily = SFPro,
                    fontSize = 11.sp,
                    color = AppTheme.palette.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onRefresh != null) {
            Text(
                text = "更新",
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = AppTheme.palette.accent,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        // Delete is a word, not an icon: the switch already owns the right-hand edge, and two unlabelled
        // glyphs next to each other would be a guessing game about which one toggles and which one removes.
        Text(
            text = "删除",
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.accent,
            modifier = Modifier
                .clip(ContinuousRoundedRectangle(8.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = source.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = AppTheme.palette.accent,
            ),
        )
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

