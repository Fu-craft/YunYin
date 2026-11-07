// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.yunyin.music.ui.style.UiStyle

/**
 * 设置主页的 **COUI(凝光)实现**。
 *
 * 与 miuix 版 [SettingsScreen] 并存,由 [UiStyle] 决定用哪一份:两套的行高、字距、卡片圆角
 * 各自按自己的规范来,所以不是"换皮",而是两份实现。经典版一行都不动。
 */
@Composable
internal fun CouiSettingsMainPage(
    uiStyle: UiStyle,
    onUiStyleChange: (UiStyle) -> Unit,
    sleepTimerLabel: String,
    onShowSleepTimer: () -> Unit,
    onOpenFootprint: () -> Unit,
    onShowPlayerStyle: () -> Unit,
    playlistShowcaseLabel: String,
    onCyclePlaylistShowcase: () -> Unit,
    onOpenLxSources: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenLogs: () -> Unit,
    hasCrashLog: Boolean,
    onOpenDownloads: () -> Unit,
    downloadWifiOnly: Boolean,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onClearLyricsCache: () -> Unit,
    onOpenProject: () -> Unit,
    nowPlayingCoverBlurBackground: Boolean,
    onNowPlayingCoverBlurBackgroundChange: (Boolean) -> Unit,
    nowPlayingAudioReactive: Boolean,
    onNowPlayingAudioReactiveChange: (Boolean) -> Unit,
    nowPlayingDynamicBackground: Boolean,
    onNowPlayingDynamicBackgroundChange: (Boolean) -> Unit,
    animatedCoverEnabled: Boolean,
    onAnimatedCoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "设置",
                    kind = CouiTextKind.PageTitle,
                )
            }
        }

        // 界面风格 —— 放在第一位:这是用户来到这里最可能想改的一件事。
        item(key = "style") {
            Group(title = "界面风格") {
                UiStyle.entries.forEachIndexed { index, style ->
                    if (index > 0) Divider()
                    Item(
                        title = {
                            Text(
                                text = style.label,
                                kind = CouiTextKind.PreferenceTitle,
                                color = if (style == uiStyle) {
                                    com.itos.coui.theme.CouiTheme.colorScheme.accent
                                } else {
                                    androidx.compose.ui.graphics.Color.Unspecified
                                },
                            )
                        },
                        summary = {
                            Text(text = style.summary, kind = CouiTextKind.PreferenceSummary)
                        },
                        onClick = { onUiStyleChange(style) },
                        trailing = {
                            if (style == uiStyle) {
                                Text(text = "使用中", kind = CouiTextKind.PreferenceSummary)
                            }
                        },
                    )
                }
            }
        }

        item(key = "play") {
            Group(title = "播放", footer = "定时关闭会在到点后自动停止播放") {
                Item(
                    title = { Text(text = "定时关闭", kind = CouiTextKind.PreferenceTitle) },
                    summary = { Text(text = sleepTimerLabel, kind = CouiTextKind.PreferenceSummary) },
                    onClick = onShowSleepTimer,
                )
                Divider()
                Item(
                    title = { Text(text = "听歌足迹", kind = CouiTextKind.PreferenceTitle) },
                    summary = {
                        Text(text = "最近 42 天的收听热力图与每日明细", kind = CouiTextKind.PreferenceSummary)
                    },
                    onClick = onOpenFootprint,
                )
                Divider()
                Item(
                    title = { Text(text = "播放器界面", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onShowPlayerStyle,
                )
                Divider()
                Item(
                    title = { Text(text = "歌单展示", kind = CouiTextKind.PreferenceTitle) },
                    summary = { Text(text = playlistShowcaseLabel, kind = CouiTextKind.PreferenceSummary) },
                    onClick = onCyclePlaylistShowcase,
                )
                Divider()
                Item(
                    title = { Text(text = "音源管理", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenLxSources,
                )
                Divider()
                Item(
                    title = { Text(text = "音效", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenEffects,
                )
            }
        }

        item(key = "nowPlaying") {
            Group(title = "正在播放外观") {
                Item(
                    title = { Text(text = "模糊封面背景", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Switch(
                            checked = nowPlayingCoverBlurBackground,
                            onCheckedChange = onNowPlayingCoverBlurBackgroundChange,
                        )
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "音频律动", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Switch(
                            checked = nowPlayingAudioReactive,
                            onCheckedChange = onNowPlayingAudioReactiveChange,
                        )
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "动态背景", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Switch(
                            checked = nowPlayingDynamicBackground,
                            onCheckedChange = onNowPlayingDynamicBackgroundChange,
                        )
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "动态封面", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Switch(
                            checked = animatedCoverEnabled,
                            onCheckedChange = onAnimatedCoverChange,
                        )
                    },
                )
            }
        }

        item(key = "storage") {
            Group(title = "下载与存储") {
                Item(
                    title = { Text(text = "下载中心", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenDownloads,
                )
                Divider()
                Item(
                    title = { Text(text = "仅 Wi-Fi 下载", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Switch(checked = downloadWifiOnly, onCheckedChange = onDownloadWifiOnlyChange)
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "清除歌词缓存", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onClearLyricsCache,
                )
            }
        }

        item(key = "about") {
            Group(title = "关于") {
                Item(
                    title = { Text(text = "版本", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Text(
                            text = com.yunyin.music.BuildConfig.VERSION_NAME,
                            kind = CouiTextKind.PreferenceSummary,
                        )
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "检查更新", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenUpdate,
                )
                Divider()
                Item(
                    title = { Text(text = "开源许可", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenLicenses,
                )
                Divider()
                Item(
                    title = { Text(text = "崩溃日志", kind = CouiTextKind.PreferenceTitle) },
                    summary = if (hasCrashLog) {
                        { Text(text = "有未查看的记录", kind = CouiTextKind.PreferenceSummary) }
                    } else {
                        null
                    },
                    onClick = onOpenLogs,
                )
                Divider()
                Item(
                    title = { Text(text = "项目主页", kind = CouiTextKind.PreferenceTitle) },
                    onClick = onOpenProject,
                )
            }
        }
    }
}
