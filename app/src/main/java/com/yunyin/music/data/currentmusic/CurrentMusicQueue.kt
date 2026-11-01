// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.currentmusic

import com.yunyin.music.core.Track
import com.yunyin.music.data.SettingsStore
import org.json.JSONObject

/**
 * 「加入一起听队列」的提交入口。
 *
 * 只在已登录 CurrentMusic 且**已加入某个房间**时可用；房间 id 由房间页写入 [SettingsStore]，
 * 这样歌单页、播放页的三点菜单都不需要知道房间是怎么来的。
 *
 * 元数据字段按参考项目 `RoomTrack.from(song)` 的形状提交：`id / name / artists / album / cover /
 * duration`。如果服务端要求不同字段，只需要改这一个函数。
 */
object CurrentMusicQueue {

    suspend fun addTrack(settings: SettingsStore, track: Track): Result<Unit> {
        val token = settings.currentMusicToken
        if (token.isBlank()) return Result.failure(IllegalStateException("未登录 CurrentMusic 账号"))
        val roomId = settings.currentMusicRoomId
        if (roomId.isBlank()) return Result.failure(IllegalStateException("尚未加入一起听房间"))
        val meta = JSONObject()
            .put("id", track.id)
            .put("name", track.name)
            .put("artists", track.artists.joinToString("/"))
            .put("album", "")
            .put("cover", track.coverUrl.orEmpty())
            .put("duration", 0L)
        return CurrentMusicApi(baseUrl = settings.currentMusicBaseUrl)
            .addToQueue(token, roomId, meta)
    }
}
