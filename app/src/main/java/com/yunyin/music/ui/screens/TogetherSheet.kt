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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.data.together.TogetherUiState
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * 一起听 — create or join a room, then show who is in it.
 *
 * ## Why the sheet is one screen with two faces rather than a flow
 *
 * Creating and joining are two taps apart at most, and the interesting state (a code to hand over)
 * is the same either way. A wizard would add screens without adding clarity, so: no room yet gets
 * both entry points, a room shows its code and its members.
 *
 * ## What this deliberately does not do
 *
 * It does not show the peer's progress or a scrubber. The room's job is to keep playback aligned;
 * surfacing the mechanism as a second progress bar would read as a second, competing player.
 */
@Composable
fun TogetherSheet(
    state: TogetherUiState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    onShareCode: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Full-bleed scrim: without this the dialog is inset by the platform's default width and the
        // scrim does not reach the screen edges, which reads as a floating card rather than a sheet.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(AppTheme.palette.background)
                    // Swallow taps so a tap inside the sheet does not dismiss it.
                    .clickable(enabled = false, indication = null, interactionSource = null) { }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "一起听",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (state.inRoom) "房间已创建，把房间码发给对方" else "和朋友同步听同一首歌",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )

                val ended = state.ended
                if (ended != null) {
                    Spacer(Modifier.height(16.dp))
                    Notice(text = ended, tone = AppTheme.palette.secondaryLabel)
                }

                Spacer(Modifier.height(18.dp))

                if (state.inRoom) {
                    RoomBody(
                        state = state,
                        onShareCode = onShareCode,
                        onCopyCode = onCopyCode,
                    )
                    Spacer(Modifier.height(18.dp))
                    SheetButton(
                        label = if (state.isHost) "结束房间" else "离开房间",
                        filled = false,
                        onClick = onLeave,
                    )
                } else {
                    IdleBody(state = state, onCreate = onCreate, onJoin = onJoin)
                }

                Spacer(Modifier.height(10.dp))
                SheetButton(label = "关闭", filled = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun IdleBody(
    state: TogetherUiState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    SheetButton(
        label = "创建房间",
        filled = true,
        onClick = onCreate,
        icon = SfIcons.Plus,
    )
    Spacer(Modifier.height(16.dp))

    Text(
        text = "或输入对方的房间码",
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = AppTheme.palette.secondaryLabel,
    )
    Spacer(Modifier.height(8.dp))

    var code by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(ContinuousRoundedRectangle(10.dp))
                .background(AppTheme.palette.secondaryBackground)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = code,
                // Uppercased as typed: the alphabet the relay uses is upper-case, and a code pasted
                // from elsewhere must not be rejected over its case.
                onValueChange = { code = it.filter { ch -> !ch.isWhitespace() }.uppercase().take(6) },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.palette.label,
                    letterSpacing = 2.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppTheme.palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (code.isEmpty()) {
                Text(
                    text = "房间码",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    color = AppTheme.palette.tertiaryLabel,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .height(46.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .background(AppTheme.palette.accent.copy(alpha = if (code.length == 6) 1f else 0.4f))
                .clickable(enabled = code.length == 6) { onJoin(code) }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "加入",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color.White,
            )
        }
    }

    state.error?.let { message ->
        Spacer(Modifier.height(12.dp))
        Notice(text = message, tone = Color(0xFFE5484D))
    }
}

@Composable
private fun RoomBody(
    state: TogetherUiState,
    onShareCode: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val code = state.code ?: return

    Box(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.cardLarge))
            .background(AppTheme.palette.secondaryBackground)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "房间码",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = code,
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                // Spaced out so the six characters read as six separate things when copied by eye.
                letterSpacing = 6.sp,
                color = AppTheme.palette.label,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            SheetButton(label = "分享", filled = true, onClick = { onShareCode(code) },
                icon = SfIcons.SquareAndArrowUp)
        }
        Box(Modifier.weight(1f)) {
            SheetButton(label = "复制", filled = false, onClick = { onCopyCode(code) })
        }
    }

    Spacer(Modifier.height(20.dp))
    Text(
        text = "房间内",
        fontFamily = SFPro,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.palette.secondaryLabel,
    )
    Spacer(Modifier.height(8.dp))

    // The members the relay knows about, plus this device (which the peer list excludes by design).
    val others = state.peers
    MemberRow(name = if (state.isHost) "我（房主）" else "我", playing = true)
    if (others.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "等待对方加入…",
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.tertiaryLabel,
        )
    } else {
        others.forEach { peer ->
            Spacer(Modifier.height(2.dp))
            MemberRow(name = peer.name.ifBlank { "对方" }, playing = peer.playing)
        }
    }

    state.error?.let { message ->
        Spacer(Modifier.height(12.dp))
        Notice(text = message, tone = Color(0xFFE5484D))
    }
}

@Composable
private fun MemberRow(name: String, playing: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(AppTheme.palette.secondaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfIcons.Person,
                contentDescription = null,
                tint = AppTheme.palette.secondaryLabel,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            fontFamily = SFPro,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (playing) {
            Icon(
                SfIcons.SpeakerWave,
                contentDescription = null,
                tint = AppTheme.palette.accent,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String, tone: Color) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = tone,
    )
}

@Composable
private fun SheetButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val palette = AppTheme.palette
    val background = if (filled) palette.accent else palette.secondaryBackground
    val ink = if (filled) Color.White else palette.label
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(background)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = ink,
        )
    }
}
