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
import com.yunyin.music.data.together.TogetherCode
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
    /** Opens the full-screen room (two avatars under headphones); null hides the button. */
    onEnterRoom: (() -> Unit)? = null,
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
                    // What to do next differs by transport: a short code is read out, a long invite is
                    // sent as a message. Saying "把房间码发给对方" over a 43-character id would be advice
                    // the user cannot follow.
                    text = when {
                        !state.inRoom -> "和朋友同步听同一首歌"
                        state.joinsByTyping -> "房间已创建，把房间码发给对方"
                        else -> "邀请已生成，分享给对方即可加入"
                    },
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
                    // The room proper, for when there is a room to be in.
                    if (onEnterRoom != null) {
                        SheetButton(
                            label = "进入一起听",
                            filled = true,
                            onClick = onEnterRoom,
                            icon = SfIcons.Headphones,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
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
    // Connecting can take a while — each candidate endpoint gets a turn — so the button says so and is
    // disabled meanwhile. Without this the sheet looked inert, which is what invited repeated taps.
    val connecting = state.connecting
    SheetButton(
        label = if (connecting) "正在连接…" else "创建房间",
        filled = true,
        enabled = !connecting,
        onClick = onCreate,
        icon = if (connecting) null else SfIcons.Plus,
    )
    Spacer(Modifier.height(16.dp))

    if (state.joinsByTyping) {
        TypedCodeJoin(state = state, connecting = connecting, onJoin = onJoin)
    } else {
        PasteInviteJoin(state = state, connecting = connecting, onJoin = onJoin)
    }

    state.error?.let { message ->
        Spacer(Modifier.height(12.dp))
        Notice(text = message, tone = Color(0xFFE5484D))
    }
}

/**
 * Joining by typing a code — the hand-rolled transports, whose codes are short and typed out.
 */
@Composable
private fun TypedCodeJoin(
    state: TogetherUiState,
    connecting: Boolean,
    onJoin: (String) -> Unit,
) {
    Text(
        // "消息服务器" would be wrong for the official transport: joining it is a couple of HTTP calls,
        // there is no broker involved. Naming the act rather than the plumbing keeps it true of both.
        text = if (connecting) "正在创建房间，请稍候…" else "或输入对方的房间码",
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = AppTheme.palette.secondaryLabel,
    )
    Spacer(Modifier.height(8.dp))

    var code by remember { mutableStateOf("") }
    // Complete for *this* transport's code format (six on the relay, twelve on a broker).
    val complete = code.count { it.isLetterOrDigit() } >= state.codeLength
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
                // Uppercased as typed, and separators are allowed and ignored: the code is meant to be
                // read out in groups, so someone will type "ABCD-2345-EFGH". The topic mapping strips
                // the same characters, so what is typed still lands on the right conversation.
                //
                // The cap comes from the transport, not a constant: codes are six characters on the
                // self-hosted relay and twelve where the code doubles as a topic name.
                onValueChange = { entered ->
                    code = entered.filter { it.isLetterOrDigit() }.uppercase().take(state.codeLength)
                },
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
        val canJoin = complete && !connecting
        JoinButton(enabled = canJoin, onClick = { onJoin(code) })
    }
}

/**
 * Joining by accepting a shared invite — the official room, whose id is a 43-character server string.
 *
 * There is no field to type into: the invite is a *(roomId, inviterId)* pair, far past what anyone keys
 * in by hand. What replaces typing is pasting, so the field is wide, unnamed, and validated by the
 * transport's own parser rather than by a length.
 */
@Composable
private fun PasteInviteJoin(
    state: TogetherUiState,
    connecting: Boolean,
    onJoin: (String) -> Unit,
) {
    Text(
        text = "或粘贴对方分享的邀请",
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = AppTheme.palette.secondaryLabel,
    )
    Spacer(Modifier.height(8.dp))

    var invite by remember { mutableStateOf("") }
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
                value = invite,
                // No case folding and no truncation: the invite's room id is lowercase hex and its
                // second half is a number, so either would corrupt it. The transport's parser is the
                // only thing that can judge it, and it does so when the button is pressed.
                onValueChange = { invite = it.trim() },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.label,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppTheme.palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (invite.isEmpty()) {
                Text(
                    text = "一起听邀请",
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.tertiaryLabel,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Enabled whenever there is something to try: the *only* thing that can tell a good invite from
        // a bad one is the transport, so the button does not pretend to pre-validate it.
        JoinButton(enabled = invite.isNotBlank() && !connecting, onClick = { onJoin(invite) })
    }
}

@Composable
private fun JoinButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(46.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.accent.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
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

@Composable
private fun RoomBody(
    state: TogetherUiState,
    onShareCode: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val code = state.code ?: return
    // What the peer needs is the invite, not the room id: the official room cannot be joined from the id
    // alone. Falling back to the code keeps the hand-rolled transports working, where the code *is* the
    // invite.
    val shareable = state.invite ?: code

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
                text = if (state.joinsByTyping) "房间码" else "邀请",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Spacer(Modifier.height(6.dp))
            if (state.joinsByTyping) {
                Text(
                    // Grouped in fours so a long code can be read out loud without losing one's place —
                    // the same reason it is not six digits.
                    text = code.chunked(4).joinToString(" "),
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = 3.sp,
                    color = AppTheme.palette.label,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // A 43-character server id is not something to read aloud, and printing it invites the
                // user to copy it by hand and paste the wrong thing. It is shown truncated for
                // recognition only, because the actual hand-over is the share button.
                Text(
                    text = code.take(8) + "…",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "分享给对方，由对方在「加入」处粘贴",
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.tertiaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            SheetButton(label = "分享", filled = true, onClick = { onShareCode(shareable) },
                icon = SfIcons.SquareAndArrowUp)
        }
        Box(Modifier.weight(1f)) {
            SheetButton(
                label = "复制",
                filled = false,
                onClick = { onCopyCode(shareable) },
            )
        }
    }

    // The endpoint in use. Shown because a transport is a rendezvous: if the two members are on
    // different servers, both rooms look empty and neither side can tell why. Seeing the address turns
    // that into something you can compare over a message.
    state.server?.let { server ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = "服务器：$server",
            fontFamily = SFPro,
            fontSize = 12.sp,
            color = AppTheme.palette.tertiaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
    /** False dims the button and stops it responding — used while a connection is in flight. */
    enabled: Boolean = true,
) {
    val palette = AppTheme.palette
    val base = if (filled) palette.accent else palette.secondaryBackground
    val background = if (enabled) base else base.copy(alpha = 0.4f)
    val ink = if (filled) Color.White else palette.label
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
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
