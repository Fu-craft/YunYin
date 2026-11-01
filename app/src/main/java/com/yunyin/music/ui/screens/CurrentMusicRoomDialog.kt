// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.currentmusic.CurrentMusicApi
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import kotlinx.coroutines.launch

/**
 * 一起听房间页。
 *
 * 布局按参考项目的房间页：标题 + 房间信息（编号/人数/身份/连接状态）+ 操作行（复制房间号 / 退出房间）
 * + 播放器卡片（点歌 / 播放器 / 刷新 / 上一首 / 播放 / 下一首）+ 队列与成员。
 *
 * 所有动作都走官方接口：`/rooms/{id}/{close|leave|play|pause|next|prev}`、`/rooms/{id}/queue`。
 */
@Composable
fun CurrentMusicRoomDialog(
    settings: SettingsStore,
    room: CurrentMusicApi.Room,
    onDismiss: () -> Unit,
) {
    val api = remember { CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl) }
    val token = settings.currentMusicToken
    val scope = rememberCoroutineScope()

    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("已连接 · 播放同步中") }
    var busy by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf("等待点歌") }
    var nowArtist by remember { mutableStateOf("所有成员都可以点歌") }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<CurrentMusicApi.SongHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            val detail = api.roomDetail(token, room.id)
            detail.onSuccess { info ->
                if (info.nowPlayingName.isNotBlank()) {
                    nowPlaying = info.nowPlayingName
                    nowArtist = info.nowPlayingArtist
                }
                queue = info.queue
            }.onFailure { status = "同步失败：${it.message}" }
            busy = false
        }
    }

    LaunchedEffect(room.id) { settings.currentMusicRoomId = room.id }
    androidx.compose.runtime.DisposableEffect(room.id) {
        onDispose { if (settings.currentMusicRoomId == room.id) settings.currentMusicRoomId = "" }
    }
    LaunchedEffect(room.id) {
        refresh()
        // Live updates: any server event refreshes the snapshot (play/pause/next/queue change).
        runCatching {
            api.roomEvents(token, room.id).collect { event ->
                if (event.contains("play") || event.contains("queue") || event.contains("sync")) refresh()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AppTheme.palette.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "返回",
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "一起听",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = AppTheme.palette.label,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = room.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = "#${room.code} · ${room.members} 人 · 房主",
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
            )
            Text(
                text = status,
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.accent,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                RoomAction("复制房间号") { status = "房间号：${room.code}" }
                Spacer(Modifier.width(20.dp))
                RoomAction("退出房间") {
                    scope.launch { api.roomAction(token, room.id, "leave"); onDismiss() }
                }
                Spacer(Modifier.width(20.dp))
                RoomAction("关闭房间") {
                    scope.launch { api.roomAction(token, room.id, "close"); onDismiss() }
                }
            }

            Spacer(Modifier.height(18.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(18.dp),
            ) {
                Text(nowPlaying, fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
                Text(nowArtist, fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.secondaryLabel)
                Spacer(Modifier.height(10.dp))
                Row {
                    RoomAction("点歌") { searching = !searching }
                    Spacer(Modifier.width(20.dp))
                    RoomAction("播放器") { status = "已打开播放器" }
                    Spacer(Modifier.width(20.dp))
                    RoomAction("刷新") { refresh() }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(SfIcons.ChevronLeft) {
                        scope.launch { api.roomAction(token, room.id, "prev"); refresh() }
                    }
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AppTheme.palette.accent)
                            .clickable {
                                scope.launch { api.roomAction(token, room.id, "play"); refresh() }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SfIcons.Play,
                            contentDescription = "播放",
                            tint = AppTheme.palette.label,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    RoundControl(SfIcons.ChevronRight) {
                        scope.launch { api.roomAction(token, room.id, "next"); refresh() }
                    }
                }
            }

            if (searching) {
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索歌曲点歌", fontFamily = SFPro, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                RoomAction("搜索") {
                    if (query.isNotBlank()) {
                        scope.launch {
                            busy = true
                            hits = api.searchSongs(query).getOrElse {
                                status = "搜索失败：${it.message}"
                                emptyList()
                            }
                            busy = false
                        }
                    }
                }
                hits.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(hit.name, fontFamily = SFPro, fontSize = 15.sp, color = AppTheme.palette.label)
                            Text(hit.artists, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
                        }
                        RoomAction("加入队列") {
                            scope.launch {
                                val meta = org.json.JSONObject()
                                    .put("id", hit.id)
                                    .put("name", hit.name)
                                    .put("artists", hit.artists)
                                    .put("album", "")
                                    .put("cover", hit.cover)
                                    .put("duration", 0L)
                                status = api.addToQueue(token, room.id, meta)
                                    .fold({ "已点歌：${hit.name}" }, { "点歌失败：${it.message}" })
                                refresh()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "点歌队列 ${queue.size}",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppTheme.palette.label,
            )
            if (queue.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "还没有点歌。进入搜索，选择“加入队列”即可提交点歌。",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            } else {
                queue.forEachIndexed { index, title ->
                    Text(
                        text = "${index + 1}. $title",
                        fontFamily = SFPro,
                        fontSize = 15.sp,
                        color = AppTheme.palette.label,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Text("同步中…", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
            }
        }
    }
}

@Composable
private fun RoomAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontFamily = SFPro,
        fontSize = 14.sp,
        color = AppTheme.palette.accent,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.palette.label, modifier = Modifier.size(26.dp))
    }
}
