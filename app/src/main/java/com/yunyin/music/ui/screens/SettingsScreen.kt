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
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

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
     * Flyme 状态栏歌词 availability and preference.
     *
     * [flymeSupported] is false on any ROM that did not port the feature — which is most of them — so
     * the row explains that rather than presenting a switch that does nothing.
     */
    flymeSupported: Boolean = false,
    statusBarLyrics: Boolean = true,
    onStatusBarLyricsChange: (Boolean) -> Unit = {},
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
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onBack),
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
            LyriconRow(
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

            SectionLabel("存储")
            ActionRow(text = "清除歌词缓存", onClick = onClearLyricsCache)

            Spacer(Modifier.height(28.dp))

            SectionLabel("关于")
            // Read from BuildConfig rather than a literal: the previous hardcoded "1.0.0" was a
            // second source of truth for the version and drifted from the manifest the moment the
            // version was bumped.
            InfoRow(text = "版本", value = com.yunyin.music.BuildConfig.VERSION_NAME)
            InfoRow(text = "歌词", value = "AMLL 逐字歌词")
        }
    }
}

/**
 * Status of the 词幕 (Lyricon) bridge.
 *
 * Three distinct states, each with different advice, which is why this is not a plain on/off row:
 * connected (nothing to do), installed but not connected (offer a retry), and not installed (name the
 * app the user needs, since nothing else in 云音 would ever mention it).
 */
@Composable
private fun LyriconRow(connected: Boolean, available: Boolean, onRetry: () -> Unit) {
    val (status, hint) = when {
        connected -> "已连接" to "歌词会显示在词幕的状态栏"
        available -> "未连接" to "点击重试连接"
        else -> "未安装" to "需先安装「词幕」应用"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(10.dp))
            .then(if (available && !connected) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "词幕状态栏歌词",
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
