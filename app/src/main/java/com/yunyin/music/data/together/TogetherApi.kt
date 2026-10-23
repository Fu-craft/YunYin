package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import com.yunyin.music.data.lxsource.LxSourceLog
import org.json.JSONArray
import org.json.JSONObject

/** Typed form of the 9 `main.js:listenTogether` operations. */
class TogetherApi(
    private val transport: TogetherTransport,
    private val log: LxSourceLog? = null,
) {
    private fun raw(action: String, detail: String, body: JSONObject) {
        val code = body.optInt("code", -1)
        log?.record(
            "解析",
            "stage=together raw action=$action${if (detail.isBlank()) "" else " $detail"} code=$code " +
                "body=${body.toString().take(1200)}",
        )
    }

    fun create(): Result<TogetherRoom> = transport
        .eapiPost("/api/listen/together/room/create", JSONObject().put("refer", "songplay_more"))
        .mapCatching { body ->
            raw("create", "", body)
            val room = TogetherRoomParser.parse(body)
            if (room.roomId.isBlank()) error("房间创建失败：${TogetherRoomParser.reason(body)}")
            room
        }

    /** `status` is the one weapi call; it is how an app that lost its room rediscovers it. */
    fun status(): Result<TogetherStatus> = transport
        .weapiPost("/listen/together/status/get", JSONObject())
        .mapCatching { body ->
            val data = body.optJSONObject("data") ?: JSONObject()
            val room = data.optJSONObject("roomInfo")
            TogetherStatus(
                inRoom = data.optBoolean("inRoom"),
                status = data.optString("status"),
                room = room?.let { TogetherRoom(it.optString("roomId"), it.optString("creatorId")) },
            )
        }

    fun check(roomId: String): Result<Boolean> = transport
        .eapiPost("/api/listen/together/room/check", JSONObject().put("roomId", roomId))
        .mapCatching { body ->
            val joinable = body.optJSONObject("data")?.optBoolean("joinable") == true
            raw("check", "roomId=$roomId joinablePath=data.joinable", body)
            joinable
        }

    fun join(roomId: String, inviterId: String): Result<TogetherRoom> = transport
        .eapiPost(
            "/api/listen/together/play/invitation/accept",
            JSONObject().put("refer", "inbox_invite").put("roomId", roomId)
                .put("inviterId", inviterId.ifBlank { "0" }),
        )
        .mapCatching { body ->
            raw("accept", "roomId=$roomId inviterId=$inviterId", body)
            val room = TogetherRoomParser.parse(body, fallbackId = roomId)
            if (room.roomId.isBlank()) error("加入失败：${TogetherRoomParser.reason(body)}")
            room
        }

    fun snapshot(roomId: String): Result<TogetherSnapshot> = transport
        .eapiPost("/api/listen/together/sync/playlist/get", JSONObject().put("roomId", roomId))
        .mapCatching { body ->
            val data = body.optJSONObject("data") ?: JSONObject()
            val playlist = data.optJSONObject("playlist") ?: JSONObject()
            val mode = playlist.optString("playMode").uppercase()
            val list = if (mode.contains("RANDOM") || mode.contains("SHUFFLE")) {
                playlist.optJSONObject("randomList")
            } else null
            val chosen = list ?: playlist.optJSONObject("displayList")
            val ids = chosen?.optJSONArray("result")?.let { array ->
                (0 until array.length()).map { array.opt(it).toString() }
            }.orEmpty()
            val raw = data.optJSONObject("playCommand") ?: data.optJSONObject("commandInfo")
            val command = raw?.let {
                val type = it.optString("commandType")
                val playStatus = it.optString("playStatus").uppercase()
                TogetherCommand(
                    accountId = it.optLong("userId").takeIf { u -> u > 0L }?.toString().orEmpty(),
                    type = type,
                    formerSongId = it.optString("formerSongId", "0"),
                    targetSongId = it.optString("targetSongId", "0"),
                    progressMs = it.optLong("progress"),
                    playing = type in setOf("PLAY", "GOTO", "NEXT", "PREV") ||
                        playStatus == "PLAY" || playStatus == "PLAYING",
                    sequence = it.optLong("serverSeq"),
                )
            }
            raw("snapshot", "roomId=$roomId playlist=${ids.size}", body)
            TogetherSnapshot(ids, command)
        }

    fun reportPlaylist(roomId: String, songIds: List<String>, accountId: Long, version: Long): Result<Unit> {
        val ids = JSONArray().apply { songIds.forEach { put(it) } }
        val playlist = JSONObject()
            .put("commandType", "REPLACE")
            .put("version", JSONArray().put(JSONObject().put("userId", accountId).put("version", version)))
            .put("anchorSongId", "")
            .put("anchorPosition", -1)
            .put("randomList", ids)
            .put("displayList", ids)
        return transport.eapiPost(
            "/api/listen/together/sync/list/command/report",
            JSONObject().put("roomId", roomId).put("playlistParam", playlist.toString()),
        ).map { }
    }

    fun reportCommand(
        roomId: String,
        type: String,
        progressMs: Long,
        playing: Boolean,
        formerSongId: String,
        targetSongId: String,
        clientSeq: Long,
    ): Result<Unit> {
        val info = JSONObject()
            .put("commandType", type)
            .put("progress", progressMs.coerceAtLeast(0L))
            .put("playStatus", if (playing) "PLAY" else "PAUSE")
            .put("formerSongId", formerSongId.ifBlank { "0" })
            .put("targetSongId", targetSongId.ifBlank { "0" })
            .put("clientSeq", clientSeq)
        return transport.eapiPost(
            "/api/listen/together/play/command/report",
            JSONObject().put("roomId", roomId).put("commandInfo", info.toString()),
        ).map { }
    }

    fun heartbeat(roomId: String, songId: String, playing: Boolean, progressMs: Long): Result<Boolean?> =
        transport.eapiPost(
            "/api/listen/together/heartbeat",
            JSONObject().put("roomId", roomId).put("songId", songId.ifBlank { "0" })
                .put("playStatus", if (playing) "PLAY" else "PAUSE")
                .put("progress", progressMs.coerceAtLeast(0L)),
        ).map { body ->
            raw("heartbeat", "roomId=$roomId", body)
            val data = body.optJSONObject("data")
            data?.optBoolean("inRoom") ?: body.optBoolean("inRoom", true)
        }

    fun end(roomId: String): Result<Unit> =
        transport.eapiPost("/api/listen/together/end/v2", JSONObject().put("roomId", roomId)).map { }

    private fun roomIdOf(body: JSONObject): String {
        val data = body.optJSONObject("data") ?: body
        return data.optJSONObject("roomInfo")?.optString("roomId")
            ?: data.optString("roomId")
            ?: ""
    }
}

data class TogetherStatus(val inRoom: Boolean, val status: String, val room: TogetherRoom?)
