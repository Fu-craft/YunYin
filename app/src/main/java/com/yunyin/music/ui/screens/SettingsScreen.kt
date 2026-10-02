package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.update.UpdateUiState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            Spacer(Modifier.width(6.dp))
            Text(
                "设置",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = AppTheme.palette.label,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
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
