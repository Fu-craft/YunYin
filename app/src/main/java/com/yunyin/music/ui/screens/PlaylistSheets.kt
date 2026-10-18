package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Playlist
import com.yunyin.music.playback.PlaybackSleepTimerRules
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro

/**
 * 新建 / 编辑歌单。
 *
 * The sheet owns only the text fields; validation and the request live in the host (`PlaylistWrites.validateName`
 * is used for the inline message, and the repository's error is shown verbatim). Same [AnimatedSheet] idiom as the
 * profile editor, so no new motion.
 */
@Composable
fun PlaylistEditorSheet(
    title: String,
    initialName: String,
    initialDescription: String,
    busy: Boolean,
    message: String?,
    onSubmit: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    AnimatedSheet(
        onDismissed = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        dismissible = !busy,
        sheetModifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) { close ->
        Text(
            text = title,
            fontFamily = SFPro,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = AppTheme.palette.label,
        )
        Spacer(Modifier.height(16.dp))
        Field(label = "歌单名称", value = name, placeholder = "例如：夜间循环", onValueChange = { name = it }, enabled = !busy)
        Spacer(Modifier.height(12.dp))
        Field(
            label = "简介（可选）",
            value = description,
            placeholder = "一句话介绍这个歌单",
            onValueChange = { description = it },
            enabled = !busy,
            singleLine = false,
        )
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(message, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.accent)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "取消",
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.secondaryLabel,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(enabled = !busy) { if (!busy) close() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(AppTheme.palette.accent.copy(alpha = if (busy) 0.5f else 1f))
                    .clickable(enabled = !busy) { if (!busy) onSubmit(name, description) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = AppTheme.palette.background,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text("保存", fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = AppTheme.palette.background)
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = true,
) {
    Text(label, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.tertiaryLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label),
            cursorBrush = SolidColor(AppTheme.palette.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 添加到歌单：the user's own playlists, loaded when the sheet opens.
 *
 * Every state is explicit — loading, error (verbatim repo message, e.g. 未登录), empty, list — because a picker that
 * silently shows nothing is the same dead end the guest path is supposed to avoid.
 */
@Composable
fun PlaylistPickerSheet(
    loading: Boolean,
    error: String?,
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        sheetModifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) { close ->
        Text("添加到歌单", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
        Spacer(Modifier.height(14.dp))
        when {
            loading -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            }
            error != null -> Text(error, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
            playlists.isEmpty() -> Text(
                "还没有自己的歌单。先在资料库新建一个歌单再回来添加。",
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            else -> playlists.forEach { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable { close { onPick(playlist) } }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
                        Text(
                            "${playlist.trackCount} 首",
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "取消",
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .clickable { close() }
                .padding(vertical = 12.dp),
        )
    }
}

/** Confirmation for a destructive action (delete playlist / remove track). */
@Composable
fun PlaylistConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = true,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        dismissible = !busy,
        sheetModifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .padding(20.dp),
    ) { close ->
        Text(title, fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = AppTheme.palette.label)
        Spacer(Modifier.height(8.dp))
        Text(message, fontFamily = SFPro, fontSize = 13.sp, lineHeight = 18.sp, color = AppTheme.palette.secondaryLabel)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "取消",
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.secondaryLabel,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(enabled = !busy) { if (!busy) close() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(AppTheme.palette.accent.copy(alpha = if (busy) 0.5f else 1f))
                    .clickable(enabled = !busy) { if (!busy) onConfirm() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "处理中…" else confirmLabel,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = AppTheme.palette.background,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (destructive) "此操作无法撤销" else "",
            fontFamily = SFPro,
            fontSize = 11.sp,
            color = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 定时关闭 picker: 关闭 / 15 / 30 / 45 / 60 分钟 / 播完当前歌. */
@Composable
fun SleepTimerSheet(
    activeMinutesLeft: Int?,
    endOfTrackArmed: Boolean,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Center,
        scrim = false,
        sheetModifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) { close ->
        Text("定时关闭", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                endOfTrackArmed -> "当前设置：播完当前歌"
                activeMinutesLeft != null -> "当前设置：还有约 $activeMinutesLeft 分钟"
                else -> "到时间后自动暂停播放"
            },
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clickable { close { onPick(null) } }.padding(vertical = 12.dp)) {
            Text("关闭定时", fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
        }
        PlaybackSleepTimerRules.OPTION_MINUTES.forEach { minutes ->
            Row(Modifier.fillMaxWidth().clickable { close { onPick(minutes) } }.padding(vertical = 12.dp)) {
                Text("$minutes 分钟", fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { close { onPick(PlaybackSleepTimerRules.END_OF_TRACK) } }.padding(vertical = 12.dp)) {
            Text("播完当前歌", fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "取消",
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedRectangle(AppleShapes.control))
                .clickable { close() }
                .padding(vertical = 12.dp),
        )
    }
}
