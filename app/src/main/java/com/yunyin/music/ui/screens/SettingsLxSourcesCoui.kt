// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.yunyin.music.data.lxsource.LxImportedSource

/**
 * 音源管理页的 **COUI(凝光)实现**。
 *
 * 语义与 miuix 版一致:一个脚本一个开关,URL 导入的脚本多一个「更新」,每行都有「删除」。
 * 差别只在组件:miuix 的 `SwitchPreference` + `bottomAction` 换成 COUI 的 `Item` + `Switch`,
 * 行内动作放进 summary 槽位(COUI 的 Item 只暴露一个尾部槽,把动作塞进有语义的文字里比塞图标更清楚)。
 */
@Composable
internal fun CouiLxSourcesPage(
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
    modifier: Modifier = Modifier,
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
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize().navigationBarsPadding(),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(text = SettingsPage.LxSources.title, kind = CouiTextKind.PageTitle)
            }
        }

        item(key = "import") {
            Group(
                title = "导入",
                footer = notes.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            ) {
                Item(
                    title = { Text(text = "从文件导入", kind = CouiTextKind.PreferenceTitle) },
                    summary = {
                        Text(
                            text = if (importing) "正在导入…" else "选择本地的 .js 音源脚本",
                            kind = CouiTextKind.PreferenceSummary,
                        )
                    },
                    enabled = !importing,
                    onClick = if (importing) null else onImport,
                )
                Divider()
                Item(
                    title = { Text(text = "从 URL 导入", kind = CouiTextKind.PreferenceTitle) },
                    summary = {
                        Text(
                            text = "拉取远端脚本；之后可以一键更新",
                            kind = CouiTextKind.PreferenceSummary,
                        )
                    },
                    enabled = !importing,
                    onClick = if (importing) null else onImportFromUrl,
                )
            }
        }

        if (sources.isNotEmpty()) {
            item(key = "installed") {
                Group(
                    title = "已导入（${sources.size}）",
                    footer = "同一个标识只能启用一个脚本。删除后脚本与它的缓存都会被移除。",
                ) {
                    sources.forEachIndexed { index, source ->
                        if (index > 0) Divider()
                        Item(
                            title = {
                                Text(text = source.header.name, kind = CouiTextKind.PreferenceTitle)
                            },
                            summary = {
                                Column {
                                    Text(
                                        text = buildString {
                                            append("支持 ").append(source.keysLabel)
                                            source.versionAuthorLabel.takeIf { it.isNotBlank() }?.let {
                                                append(" · ").append(it)
                                            }
                                        },
                                        kind = CouiTextKind.PreferenceSummary,
                                    )
                                    source.originUrl?.let { origin ->
                                        Text(text = origin, kind = CouiTextKind.Description)
                                    }
                                    Row(
                                        Modifier.padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.Start,
                                    ) {
                                        if (source.originUrl != null) {
                                            Text(
                                                text = "更新",
                                                kind = CouiTextKind.PreferenceValue,
                                                modifier = Modifier
                                                    .clickable { onRefresh(source.id) }
                                                    .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                                            )
                                            Spacer(Modifier.width(16.dp))
                                        }
                                        Text(
                                            text = "删除",
                                            kind = CouiTextKind.PreferenceValue,
                                            color = com.itos.coui.theme.CouiTheme.colorScheme.accent,
                                            modifier = Modifier
                                                .clickable { onDelete(source.id) }
                                                .padding(top = 4.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                            },
                            trailing = {
                                Switch(
                                    checked = source.enabled,
                                    onCheckedChange = { enabled -> onToggle(source.id, enabled) },
                                )
                            },
                        )
                    }
                    if (errorLogAvailable) {
                        Divider()
                        Item(
                            title = { Text(text = "复制音源错误日志", kind = CouiTextKind.PreferenceTitle) },
                            summary = {
                                Text(
                                    text = "包含脚本失败时的详细调用栈",
                                    kind = CouiTextKind.PreferenceSummary,
                                )
                            },
                            onClick = onCopyErrorLog,
                        )
                    }
                }
            }
        }
    }
}
