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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.slider.Slider
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.yunyin.music.audio.EqualizerPreset
import com.yunyin.music.audio.EqualizerSettings
import com.yunyin.music.data.update.UpdateUiState
import kotlin.math.roundToInt

/**
 * 设置子页的 **COUI(凝光)实现**。
 *
 * 与 [SettingsScreen] 里的 miuix 版本并存,由 [com.yunyin.music.ui.style.UiStyle] 选择。
 * 每个页面都保持自己原来的语义与文案:换的只是组件与材质,不是信息结构。
 */

/** COUI 子页共用的外壳:大标题 + 可滚动分组。 */
@Composable
private fun CouiSubpage(
    title: String,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
    ) {
        item(key = "title") {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, kind = CouiTextKind.PageTitle)
            }
        }
        content()
    }
}

@Composable
internal fun CouiLicensesPage(modifier: Modifier = Modifier) {
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
            Triple("io.github.itoseo:coui", "MPL-2.0", "https://github.com/itoseo/COUI-Kit"),
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

    CouiSubpage(title = SettingsPage.Licenses.title, modifier = modifier) {
        item(key = "licenses") {
            Group(
                title = "组件与许可证",
                footer = "点击任意一行可打开上游仓库；完整声明见仓库 NOTICE。",
            ) {
                licenses.forEachIndexed { index, (name, license, url) ->
                    if (index > 0) Divider()
                    Item(
                        title = { Text(text = name, kind = CouiTextKind.PreferenceTitle) },
                        trailing = { Text(text = license, kind = CouiTextKind.PreferenceValue) },
                        onClick = { runCatching { uriHandler.openUri(url) } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CouiUpdatePage(
    state: UpdateUiState,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes = when (state) {
        is UpdateUiState.Available -> state.update.notes
        is UpdateUiState.AvailableSkipped -> state.update.notes
        is UpdateUiState.Downloading -> state.update.notes
        is UpdateUiState.Failed -> state.update?.notes
        is UpdateUiState.Idle -> null
    }
    val failure = (state as? UpdateUiState.Failed)?.message

    CouiSubpage(title = SettingsPage.Update.title, modifier = modifier) {
        item(key = "version") {
            Group(
                title = "版本",
                footer = "更新包从 GitHub Release 下载，安装时由系统弹窗确认；应用不会自行安装。",
            ) {
                Item(
                    title = { Text(text = "当前版本", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Text(
                            text = com.yunyin.music.BuildConfig.VERSION_NAME,
                            kind = CouiTextKind.PreferenceValue,
                        )
                    },
                )
                Divider()
                Item(
                    title = { Text(text = "检查状态", kind = CouiTextKind.PreferenceTitle) },
                    trailing = {
                        Text(text = updateRowValue(state), kind = CouiTextKind.PreferenceValue)
                    },
                )
                if (failure != null) {
                    Divider()
                    Item(
                        title = { Text(text = "失败原因", kind = CouiTextKind.PreferenceTitle) },
                        summary = {
                            Text(text = failure, kind = CouiTextKind.PreferenceSummary)
                        },
                    )
                }
                Divider()
                Item(
                    title = { Text(text = "立即检查", kind = CouiTextKind.PreferenceTitle) },
                    summary = {
                        Text(
                            text = "向 GitHub 查询最新 Release",
                            kind = CouiTextKind.PreferenceSummary,
                        )
                    },
                    onClick = onCheckUpdate,
                )
            }
        }

        if (!notes.isNullOrBlank()) {
            item(key = "notes") {
                Group(title = "更新说明") {
                    Item(
                        title = {
                            Text(text = notes, kind = CouiTextKind.Body)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CouiLogsPage(
    hasCrashLog: Boolean,
    onCopyCrashLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CouiSubpage(title = SettingsPage.Logs.title, modifier = modifier) {
        item(key = "crash") {
            Group(
                title = "崩溃日志",
                footer = "日志写在应用私有目录，未 root 的设备无法用文件管理器打开，所以提供复制到剪贴板。",
            ) {
                if (hasCrashLog) {
                    Item(
                        title = { Text(text = "复制上次崩溃日志", kind = CouiTextKind.PreferenceTitle) },
                        summary = {
                            Text(
                                text = "把崩溃时的调用栈放进剪贴板",
                                kind = CouiTextKind.PreferenceSummary,
                            )
                        },
                        onClick = onCopyCrashLog,
                    )
                } else {
                    Item(
                        title = { Text(text = "暂无崩溃记录", kind = CouiTextKind.PreferenceTitle) },
                        summary = {
                            Text(
                                text = "最近一次运行没有未捕获的异常",
                                kind = CouiTextKind.PreferenceSummary,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CouiEffectsPage(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    preset: String,
    onPresetChange: (String) -> Unit,
    bands: List<Int>,
    onBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A drag writes to the prefs once, on release — not on every frame.
    var draft by remember(bands) { mutableStateOf(bands) }

    CouiSubpage(title = SettingsPage.Effects.title, modifier = modifier) {
        item(key = "equalizer") {
            Group(
                title = "均衡器",
                footer = "使用系统 Equalizer，只作用于云音自己的音频会话；设备不支持时会自动降级为关闭。",
            ) {
                Item(
                    title = { Text(text = "启用均衡器", kind = CouiTextKind.PreferenceTitle) },
                    summary = {
                        Text(text = "系统级 EQ，仅影响本应用的播放", kind = CouiTextKind.PreferenceSummary)
                    },
                    trailing = {
                        Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    },
                )
                EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.forEach { option ->
                    Divider()
                    Item(
                        title = { Text(text = option.label, kind = CouiTextKind.PreferenceTitle) },
                        enabled = enabled,
                        trailing = {
                            if (preset == option.name) {
                                Text(text = "✓", kind = CouiTextKind.PreferenceValue)
                            }
                        },
                        onClick = if (enabled) ({ onPresetChange(option.name) }) else null,
                    )
                }
            }
        }

        if (enabled) {
            item(key = "bands") {
                Group(
                    title = "频段",
                    footer = "±12 dB，拖到 0 即为平直。手动调整后会切到「自定义」。",
                ) {
                    EqualizerSettings.FREQUENCIES.forEachIndexed { index, hz ->
                        if (index > 0) Divider()
                        val gain = draft.getOrElse(index) { 0 }
                        Column(Modifier.padding(horizontal = 0.dp)) {
                            Item(
                                title = { Text(text = "$hz Hz", kind = CouiTextKind.PreferenceTitle) },
                                trailing = {
                                    Text(
                                        text = couiFormatGain(gain),
                                        kind = CouiTextKind.PreferenceValue,
                                    )
                                },
                            )
                            Slider(
                                value = gain.toFloat(),
                                onValueChange = { raw ->
                                    draft = draft.toMutableList().also { it[index] = raw.roundToInt() }
                                },
                                onValueChangeFinished = {
                                    onBandChange(index, draft.getOrElse(index) { gain })
                                },
                                valueRange = -1200f..1200f,
                                steps = 23,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Millibels as a signed dB label, the unit the platform effect actually stores. */
private fun couiFormatGain(millibel: Int): String {
    val db = millibel / 100.0
    return if (db > 0) "+%.1f dB".format(db) else "%.1f dB".format(db)
}
