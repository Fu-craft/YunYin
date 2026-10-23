package com.yunyin.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.yunyin.music.data.together.TogetherSession

/** 一起听 sheet (ported interaction from QPlayer; two logged-in accounts are required). */
@Composable
fun TogetherSheet(session: TogetherSession, onDismiss: () -> Unit) {
    val state by session.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var invite by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一起听") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("需要两个已登录账号 · 房间通过网易云官方协议同步")
                if (state.inRoom) {
                    Text("房间：${state.roomId}")
                    if (state.inviteLink.isNotEmpty()) {
                        OutlinedTextField(
                            value = state.inviteLink,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            label = { Text("邀请链接") },
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.inviteLink)) }) {
                            Text("复制邀请链接")
                        }
                    }
                    state.error.takeIf { it.isNotBlank() }?.let { Text(it) }
                    TextButton(onClick = { session.leave(); onDismiss() }) { Text("退出房间") }
                } else {
                    OutlinedTextField(
                        value = invite,
                        onValueChange = { invite = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("粘贴邀请链接或房间 ID") },
                    )
                    TextButton(onClick = { session.join(invite) }) { Text("加入房间") }
                    TextButton(onClick = { session.create() }) { Text("创建房间（需当前有歌）") }
                    state.error.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(session.logDump())) }) {
                    Text("复制一起听日志")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
    // Keep the ported entry discoverable even if the dialog is dismissed during a reconnect.
    if (state.error.contains("过于频繁")) Text(state.error)
}
