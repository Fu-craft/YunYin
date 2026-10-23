package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import com.yunyin.music.data.lxsource.LxSourceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Typed form of the 9 `main.js:listenTogether` operations. Network runs on IO; playback stays on Main. */
class TogetherApi(
    private val transport: TogetherTransport,
    private val log: LxSourceLog? = null,
) {
    @Volatile var lastCreateUrl: String = ""
    @Volatile var lastCreatePayload: String = ""
    @Volatile var lastCreateBody: String = ""
    @Volatile var lastCreateCandidates: String = ""
    @Volatile var lastCreateKeys: String = ""

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun raw(action: String, detail: String, body: JSONObject) {
        log?.record(
            "解析",
            "stage=together raw action=$action${if (detail.isBlank()) "" else " $detail"} " +
                "code=${body.optInt("code", -1)} body=${body.toString().take(1200)}",
        )
    }

    suspend fun create(): Result<TogetherRoom> = io {
        transport.eapiPost("/api/listen/together/room/create", JSONObject().put("refer", "songplay_more"))
    }.mapCatching { body ->
        lastCreateUrl = transport.lastUrl
        lastCreatePayload = transport.lastPlainPayload
        lastCreateBody = body.toString()
        val extraction = TogetherRoomParser.extract(body)
        lastCreateCandidates = extraction.candidates.joinToString(",")
        lastCreateKeys = body.keys().asSequence().joinToString(",")
        log?.record(
            "解析",
            "stage=together create url=$lastCreateUrl payload=${lastCreatePayload.take(1000)} " +
                "code=${body.optInt("code", -1)} body=${lastCreateBody.take(4000)}",
        )
        log?.record(
            "解析",
            "stage=together create roomId=${extraction.roomId.ifBlank { "none" }} " +
                "candidates=[$lastCreateCandidates] rawKeys=$lastCreateKeys",
        )
        if (extraction.roomId.isBlank()) error("房间创建失败（未返回房间号）：${TogetherRoomParser.reason(body)}")
        TogetherRoomParser.parse(body)
    }

    suspend fun status(): Result<TogetherStatus> = io {
        transport.weapiPost("/listen/together/status/get", JSONObject())
    }.fold(
        onSuccess = { body ->
            val data = body.optJSONObject("data") ?: JSONObject()
            val room = data.optJSONObject("roomInfo")
            val raw = data.optJSONObject("inRoom")
            Result.success(
                TogetherStatus(
                    inRoom = if (data.has("inRoom")) data.optBoolean("inRoom") else null,
                    status = data.optString("status"),
                    room = room?.let { TogetherRoom(it.optString("roomId"), it.optString("creatorId")) },
                ),
            )
        },
        onFailure = {
            // Some deployments answer the weapi status route with an empty body; that is "unknown", not a failure
            // that may stop the tick or end the room. The snapshot remains the source of room truth.
            log?.record("解析", "stage=together status=empty reason=${it.message}")
            Result.success(TogetherStatus(inRoom = null, status = "empty", room = null))
        },
    )

    suspend fun check(roomId: String): Result<Boolean> = io {
        transport.eapiPost("/api/listen/together/room/check", JSONObject().put("roomId", roomId))
    }.mapCatching { body ->
        val joinable = body.optJSONObject("data")?.optBoolean("joinable") == true
        raw("check", "roomId=$roomId joinablePath=data.joinable", body)
        joinable
    }

    suspend fun join(roomId: String, inviterId: String): Result<TogetherRoom> = io {
        transport.eapiPost(
            "/api/listen/together/play/invitation/accept",
            JSONObject().put("refer", "inbox_invite").put("roomId", roomId)
                .put("inviterId", inviterId.ifBlank { "0" }),
        )
    }.mapCatching { body ->
        raw("accept", "roomId=$roomId inviterId=$inviterId", body)
        val room = TogetherRoomParser.parse(body, fallbackId = roomId)
        if (room.roomId.isBlank()) error("加入失败：${TogetherRoomParser.reason(body)}")
        room
    }

    suspend fun snapshot(roomId: String): Result<TogetherSnapshot> = io {
        transport.eapiPost("/api/listen/together/sync/playlist/get", JSONObject().put("roomId", roomId))
    }.mapCatching { body ->
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
        val rawCommand = data.optJSONObject("playCommand") ?: data.optJSONObject("commandInfo")
        val command = rawCommand?.let {
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

    suspend fun reportPlaylist(roomId: String, songIds: List<String>, accountId: Long, version: Long): Result<Unit> {
        val playlist = TogetherSeedRules.playlistParam(songIds, accountId, version)
        return io {
            transport.eapiPost(
                "/api/listen/together/sync/list/command/report",
                JSONObject().put("roomId", roomId).put("playlistParam", playlist.toString()),
            )
        }.map { }
    }

    suspend fun reportCommand(
        roomId: String,
        type: String,
        progressMs: Long,
        playing: Boolean,
        formerSongId: String,
        targetSongId: String,
        clientSeq: Long,
    ): Result<Unit> {
        val info = TogetherSeedRules.commandInfo(type, progressMs, playing, formerSongId, targetSongId, clientSeq)
        return io {
            transport.eapiPost(
                "/api/listen/together/play/command/report",
                JSONObject().put("roomId", roomId).put("commandInfo", info.toString()),
            )
        }.map { }
    }

    suspend fun heartbeat(roomId: String, songId: String, playing: Boolean, progressMs: Long): Result<Boolean?> = io {
        transport.eapiPost(
            "/api/listen/together/heartbeat",
            JSONObject().put("roomId", roomId).put("songId", songId.ifBlank { "0" })
                .put("playStatus", if (playing) "PLAY" else "PAUSE")
                .put("progress", progressMs.coerceAtLeast(0L)),
        )
    }.map { body ->
        raw("heartbeat", "roomId=$roomId", body)
        val data = body.optJSONObject("data")
        data?.optBoolean("inRoom") ?: body.optBoolean("inRoom", true)
    }

    suspend fun end(roomId: String, explicit: Boolean): Result<Unit> {
        if (!TogetherRules.canEnd(explicit, roomId)) {
            log?.record(
                "解析",
                "stage=together end skipped reason=${if (roomId.isBlank()) "no-room" else "not-explicit"}",
            )
            return Result.failure(IllegalStateException("end requires explicit user action and a room id"))
        }
        return io {
            transport.eapiPost("/api/listen/together/end/v2", JSONObject().put("roomId", roomId))
        }.map { }
    }
}

data class TogetherStatus(val inRoom: Boolean?, val status: String, val room: TogetherRoom?)
