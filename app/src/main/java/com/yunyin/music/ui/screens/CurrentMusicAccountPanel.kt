// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.currentmusic.CurrentMusicApi
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * CurrentMusic 站内账号面板。
 *
 * 只做官方文档里的三个调用：`auth/phone/code`（发验证码）、`auth/register`、`auth/login`，
 * 成功后把 token 存进 [SettingsStore]，一起听（`/rooms*`）全靠它。
 *
 * 界面刻意保持一行一个状态：这些字段都是必填，错误必须能看见是哪一步失败。
 */
@Composable
fun CurrentMusicAccountPanel(
    settings: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var registered by remember { mutableStateOf(settings.currentMusicToken.isNotBlank()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (registered) {
            Text(
                text = "已登录 CurrentMusic 账号",
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = "一起听房间将使用这个账号",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Spacer(Modifier.height(8.dp))
            var rooms by remember { mutableStateOf<List<CurrentMusicApi.Room>>(emptyList()) }
            var roomMessage by remember { mutableStateOf("") }
            var openRoom by remember { mutableStateOf<CurrentMusicApi.Room?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "刷新房间",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickable {
                        busy = true
                        scope.launch {
                            val api = CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
                            rooms = api.rooms(settings.currentMusicToken).getOrElse {
                                roomMessage = "房间列表失败：${it.message}"
                                emptyList()
                            }
                            busy = false
                        }
                    },
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "建房",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickable {
                        busy = true
                        scope.launch {
                            val api = CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
                            roomMessage = api.createRoom(settings.currentMusicToken, "云音的一起听")
                                .fold({ "房间已创建：${it.code}" }, { "建房失败：${it.message}" })
                            busy = false
                        }
                    },
                )
            }
            if (roomMessage.isNotBlank()) {
                Text(roomMessage, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
            }
            rooms.forEach { room ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(room.name, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label)
                        Text(
                            text = "${room.members} 人 · 房间码 ${room.code}",
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }
                    Text(
                        text = "加入",
                        fontFamily = SFPro,
                        fontSize = 14.sp,
                        color = AppTheme.palette.accent,
                        modifier = Modifier.clickable {
                            scope.launch {
                                val api = CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
                                roomMessage = api.joinRoom(settings.currentMusicToken, room.id)
                                    .fold({ openRoom = room; "已加入 ${room.name}" }, { "加入失败：${it.message}" })
                            }
                        },
                    )
                }
            }
            openRoom?.let { active ->
                CurrentMusicRoomDialog(
                    settings = settings,
                    room = active,
                    onDismiss = { openRoom = null },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "退出登录",
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.accent,
                modifier = Modifier.clickable {
                    settings.currentMusicToken = ""
                    registered = false
                },
            )
            return@Column
        }

        Text(
            text = if (registerMode) "注册 CurrentMusic 账号" else "登录 CurrentMusic 账号",
            fontFamily = SFPro,
            fontSize = 15.sp,
            color = AppTheme.palette.label,
        )
        Spacer(Modifier.height(6.dp))
        Field("用户名", username) { username = it }
        Field("密码", password, secret = true) { password = it }
        if (registerMode) {
            Field("昵称（可留空）", nickname) { nickname = it }
            Field("手机号", contact, number = true) { contact = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("验证码", code, number = true) { code = it } }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "发送验证码",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = if (busy) AppTheme.palette.tertiaryLabel else AppTheme.palette.accent,
                    modifier = Modifier.clickable(enabled = !busy) {
                        busy = true
                        message = ""
                        scope.launch {
                            val api = CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
                            message = api.sendPhoneCode(contact)
                                .fold({ "验证码已发送，请查收短信" }, { "发送失败：${it.message}" })
                            busy = false
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AppTheme.palette.accent)
                .clickable(enabled = !busy) {
                    busy = true
                    message = ""
                    scope.launch {
                        val api = CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
                        val result = if (registerMode) {
                            api.register(
                                username = username,
                                password = password,
                                nickname = nickname,
                                contact = contact,
                                code = code,
                                phone = true,
                                device = "云音 Android",
                            )
                        } else {
                            api.login(username = username, password = password, device = "云音 Android")
                        }
                        message = result.fold(
                            onSuccess = { session ->
                                settings.currentMusicToken = session.token
                                registered = true
                                "已登录：${session.account.nickname.ifBlank { session.account.username }}"
                            },
                            onFailure = { "失败：${it.message}" },
                        )
                        busy = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (busy) "处理中…" else if (registerMode) "注册并登录" else "登录",
                fontFamily = SFPro,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (registerMode) "已有账号？去登录" else "没有账号？去注册",
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.accent,
            modifier = Modifier.clickable { registerMode = !registerMode; message = "" },
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = SFPro, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
