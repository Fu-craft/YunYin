package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.import.ImportedLine
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro

/** Where the import dialog is in its two-step flow. */
enum class ImportPlaylistPhase { Input, Working, Preview, Done }

enum class ImportSource(val label: String) { Text("文本"), NeteaseLink("网易云链接") }

/** One resolved input line: a matched track, or null when nothing acceptable was found. */
data class ImportRowUi(val line: ImportedLine, val track: Track?)

/**
 * 导入歌单 dialog: paste text or a NetEase playlist link, then review matched/unmatched rows before writing.
 *
 * Skill decisions: two explicit source modes instead of guessing from the text; a preview step that lists every
 * unmatched line (nothing silently dropped); one primary action per phase and 44 dp controls; the unmatched list is
 * visually quieter than the primary summary so the action stays obvious.
 */
@Composable
fun ImportPlaylistSheet(
    phase: ImportPlaylistPhase,
    source: ImportSource,
    name: String,
    text: String,
    rows: List<ImportRowUi>,
    status: String?,
    matchedCount: Int,
    onSourceChange: (ImportSource) -> Unit,
    onNameChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onResolve: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        sheetModifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .padding(20.dp),
    ) { close ->
        Text("导入歌单", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
        Spacer(Modifier.height(4.dp))
        Text(
            when (phase) {
                ImportPlaylistPhase.Input, ImportPlaylistPhase.Working -> "从文本或网易云歌单链接创建一个新歌单"
                ImportPlaylistPhase.Preview -> "检查匹配结果，未匹配的行会跳过"
                ImportPlaylistPhase.Done -> status ?: "导入完成"
            },
            fontFamily = SFPro,
            fontSize = 12.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        Spacer(Modifier.height(14.dp))

        when (phase) {
            ImportPlaylistPhase.Input, ImportPlaylistPhase.Working -> {
                Field("歌单名称", name) { onNameChange(it) }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(AppTheme.palette.secondaryBackground)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImportSource.entries.forEach { option ->
                        Box(
                            Modifier.weight(1f).height(36.dp)
                                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                .background(if (option == source) AppTheme.palette.tertiaryBackground else Color.Transparent)
                                .clickable(
                                    indication = rememberControlRipple(bounded = true),
                                    interactionSource = null,
                                ) { onSourceChange(option) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.label,
                                fontFamily = SFPro,
                                fontWeight = if (option == source) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = 13.sp,
                                color = if (option == source) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .background(AppTheme.palette.secondaryBackground)
                        .padding(12.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            if (source == ImportSource.Text) "每行一首：歌名 - 歌手\n例如：夜曲 - 周杰伦" else "粘贴网易云歌单链接或歌单 ID",
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.tertiaryLabel,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.label),
                        cursorBrush = SolidColor(AppTheme.palette.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    )
                }
                if (status != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton("取消", enabled = phase != ImportPlaylistPhase.Working) { close() }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        if (phase == ImportPlaylistPhase.Working) "解析中…" else "解析并匹配",
                        enabled = phase != ImportPlaylistPhase.Working && text.isNotBlank(),
                        onClick = onResolve,
                    )
                }
            }
            ImportPlaylistPhase.Preview -> {
                Text(
                    "匹配 $matchedCount 首 · 未匹配 ${rows.count { it.track == null }} 行",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.height(8.dp))
                val unmatched = rows.filter { it.track == null }
                if (unmatched.isEmpty()) {
                    Text("全部匹配成功", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(unmatched) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.line.raw, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("未匹配，将跳过", fontFamily = SFPro, fontSize = 11.sp, color = AppTheme.palette.tertiaryLabel)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    TextButton("返回") { onBack() }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("导入 $matchedCount 首", enabled = matchedCount > 0) { onConfirm() }
                }
            }
            ImportPlaylistPhase.Done -> {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    PrimaryButton("完成") { close() }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(label, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .background(AppTheme.palette.secondaryBackground)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.label),
                cursorBrush = SolidColor(AppTheme.palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .clickable(enabled = enabled, indication = rememberControlRipple(bounded = false), interactionSource = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = SFPro, fontSize = 14.sp, color = if (enabled) AppTheme.palette.secondaryLabel else AppTheme.palette.tertiaryLabel)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (enabled) AppTheme.palette.accent else AppTheme.palette.tertiaryBackground)
            .clickable(enabled = enabled, indication = rememberControlRipple(bounded = true), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (enabled) Color.White else AppTheme.palette.secondaryLabel)
    }
}
