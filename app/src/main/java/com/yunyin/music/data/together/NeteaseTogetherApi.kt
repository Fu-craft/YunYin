package com.yunyin.music.data.together

import com.yunyin.music.core.TogetherRemoteState
import com.yunyin.music.core.TogetherRoomInfo
import com.yunyin.music.core.TogetherStatus
import com.yunyin.music.core.TogetherUser
import com.yunyin.music.data.net.EapiClient
import org.json.JSONObject

/**
 * Listen-together, spoken the way NetEase's own client speaks it.
 *
 * ## The routes, and why they are these
 *
 * Every path below was probed against NetEase directly. A real route answers `301 需要登录` without a
 * session (or `400` when required parameters are missing), while a non-existent one answers the web
 * server's 404 — and that is how these were confirmed rather than guessed:
 *
 * | operation | path | note |
 * |---|---|---|
 * | create | `/api/listen/together/room/create` | `refer` |
 * | check | `/api/listen/together/room/check` | |
 * | join | `/api/listen/together/play/invitation/accept` | `refer`, `roomId`, `inviterId` |
 * | **read** | `/api/listen/together/sync/playlist/get` | the whole reason this feature can work |
 * | playlist | `/api/listen/together/sync/list/command/report` | `playlistParam` (JSON) |
 * | command | `/api/listen/together/play/command/report` | `commandInfo` (JSON) |
 * | heartbeat | `/api/listen/together/heartbeat` | |
 * | end | `/api/listen/together/end/v2` | |
 * | status | `/api/listen/together/status/get` | |
 *
 * The two that differ from what this app used before are the ones that mattered: the command is
 * `play/command/report` (not `play/command`), and the keep-alive is `heartbeat` (not `heatbeat`). Those
 * older spellings belong to the API proxy, and `/api/listen/together/heatbeat` is a 404 on NetEase itself.
 *
 * ## Payloads are JSON blobs, not flat fields
 *
 * `playlistParam` and `commandInfo` are stringified objects. That is the reference implementation's shape and
 * the only one confirmed to work; sending those values as top-level fields is a different request that a
 * server is free to accept and ignore.
 */
class NeteaseTogetherApi(private val client: EapiClient) {

    /**
     * A `Result` from the transport as the session's own outcome type.
     *
     * The session distinguishes "the room is gone" from "the network is down", so an error has to arrive as
     * [TogetherResult.Failed] rather than as an exception the caller has to remember to catch.
     */
    private fun <T, R> Result<T>.mapTogether(transform: (T) -> R): TogetherResult<R> = fold(
        onSuccess = { TogetherResult.Ok(transform(it)) },
        onFailure = { TogetherResult.Failed(it.message ?: "一起听请求失败") },
    )

    /** Same, for the calls that return nothing useful. */
    private fun <T> Result<T>.toTogether(): TogetherResult<Unit> = fold(
        onSuccess = { TogetherResult.Ok(Unit) },
        onFailure = { TogetherResult.Failed(it.message ?: "一起听请求失败") },
    )


    /**
     * Opens a room. `refer` is the entry point the official client reports — both creating and joining send
     * one, and a room made without it is a room whose semantics have not been verified.
     */
    suspend fun create(): TogetherResult<TogetherRoomInfo> =
        client.call("/api/listen/together/room/create", mapOf("refer" to "songplay_more"))
            .mapTogether { json ->
                // The room may be nested (`data.roomInfo`) or be `data` itself, depending on the route.
                val data = json.optJSONObject("data") ?: JSONObject()
                val info = data.optJSONObject("roomInfo") ?: data
                TogetherRoomInfo(
                    roomId = info.optString("roomId"),
                    inviterId = info.optLong("creatorId"),
                ).also {
                    TogetherLog.add("API create room=${TogetherLog.short(it.roomId)} inviter=${it.inviterId}")
                }
            }

    suspend fun check(roomId: String): TogetherResult<Boolean> =
        client.call("/api/listen/together/room/check", mapOf("roomId" to roomId))
            .mapTogether { (it.optJSONObject("data") ?: JSONObject()).optBoolean("joinable") }

    suspend fun accept(roomId: String, inviterId: Long): TogetherResult<Unit> =
        client.call(
            "/api/listen/together/play/invitation/accept",
            mapOf("refer" to "inbox_invite", "roomId" to roomId, "inviterId" to inviterId.toString()),
        ).toTogether().also { TogetherLog.add("API accept room=${TogetherLog.short(roomId)} -> $it") }

    /** The read path: the room's one current command, plus its queue. */
    suspend fun snapshot(roomId: String): TogetherResult<TogetherRemoteState> =
        client.call("/api/listen/together/sync/playlist/get", mapOf("roomId" to roomId))
            .mapTogether { json ->
                val data = json.optJSONObject("data") ?: JSONObject()
                val command = data.optJSONObject("playCommand") ?: data.optJSONObject("commandInfo")
                val playlist = data.optJSONObject("playlist") ?: JSONObject()
                val mode = playlist.optString("playMode").uppercase()
                // The list is read from whichever shape the server returns. What the client *sends* is a
                // bare array (`displayList: [id, id]`), while the server's own replies wrap it as an object
                // with `result`. Accepting only one of the two silently produced an empty queue — and an
                // empty queue on the follower is exactly what makes its next/previous buttons do nothing.
                val luckyFirst = mode.contains("RANDOM") || mode.contains("SHUFFLE")
                val ids = listOf("randomList", "displayList")
                    .let { if (luckyFirst) it else it.reversed() }
                    .firstNotNullOfOrNull { key ->
                        playlist.optJSONArray(key)
                            ?: playlist.optJSONObject(key)?.optJSONArray("result")
                    }
                val queue = (0 until (ids?.length() ?: 0)).mapNotNull { i ->
                    ids?.optString(i)?.toLongOrNull()?.takeIf { it > 0L }
                }
                TogetherRemoteState(
                    userId = command?.optLong("userId") ?: 0L,
                    songId = command?.optString("targetSongId")?.toLongOrNull() ?: 0L,
                    positionMs = command?.optLong("progress") ?: 0L,
                    playing = commandPlaying(
                        type = command?.optString("commandType").orEmpty(),
                        playStatus = command?.optString("playStatus").orEmpty(),
                    ),
                    serverSeq = command?.optLong("serverSeq") ?: 0L,
                    commandType = command?.optString("commandType").orEmpty().uppercase(),
                    queue = queue,
                ).also {
                    TogetherLog.add(
                        "API snapshot cmd=${it.hasCommand} song=${it.songId} author=${it.userId} " +
                            "type=${it.commandType} play=${it.playing} queue=${it.queue.size}",
                    )
                }
            }

    /** Publishes the queue. `playlistParam` is a stringified object, as the official client sends it. */
    suspend fun reportPlaylist(roomId: String, accountId: Long, version: Long, songIds: List<Long>): TogetherResult<Unit> {
        val playlist = JSONObject()
            .put("commandType", "REPLACE")
            .put("version", org.json.JSONArray().put(
                JSONObject().put("userId", accountId).put("version", version),
            ))
            .put("anchorSongId", "")
            .put("anchorPosition", -1)
            .put("randomList", org.json.JSONArray(songIds))
            .put("displayList", org.json.JSONArray(songIds))
        return client.call(
            "/api/listen/together/sync/list/command/report",
            mapOf("roomId" to roomId, "playlistParam" to playlist.toString()),
        ).toTogether().also { TogetherLog.add("API playlist ${songIds.size} songs -> $it") }
    }

    /** Publishes a discrete action. `commandInfo` is a stringified object. */
    suspend fun reportCommand(
        roomId: String,
        type: String,
        formerSongId: String,
        targetSongId: String,
        progressMs: Long,
        playing: Boolean,
        sequence: Long,
    ): TogetherResult<Unit> {
        val command = JSONObject()
            .put("commandType", type)
            .put("progress", progressMs.coerceAtLeast(0L))
            .put("playStatus", if (playing) "PLAY" else "PAUSE")
            .put("formerSongId", formerSongId)
            .put("targetSongId", targetSongId)
            .put("clientSeq", sequence)
        return client.call(
            "/api/listen/together/play/command/report",
            mapOf("roomId" to roomId, "commandInfo" to command.toString()),
        ).toTogether().also { TogetherLog.add("API command $type song=$targetSongId -> $it") }
    }

    suspend fun heartbeat(roomId: String, songId: Long, playing: Boolean, progressMs: Long): TogetherResult<Unit> =
        client.call(
            "/api/listen/together/heartbeat",
            mapOf(
                "roomId" to roomId,
                "songId" to songId.toString(),
                "playStatus" to if (playing) "PLAY" else "PAUSE",
                "progress" to progressMs.coerceAtLeast(0L),
            ),
        ).toTogether()

    suspend fun status(): TogetherResult<TogetherStatus> =
        client.call("/api/listen/together/status/get")
            .mapTogether { json ->
                val data = json.optJSONObject("data") ?: JSONObject()
                val info = data.optJSONObject("roomInfo") ?: JSONObject()
                val users = info.optJSONArray("roomUsers")
                val members = (0 until (users?.length() ?: 0)).mapNotNull { i ->
                    val u = users?.optJSONObject(i) ?: return@mapNotNull null
                    TogetherUser(
                        uid = u.optLong("userId"),
                        nickname = u.optString("nickname"),
                        avatarUrl = u.optString("avatarUrl").ifBlank { null },
                    )
                }
                TogetherStatus(
                    inRoom = data.optBoolean("inRoom"),
                    roomId = info.optString("roomId"),
                    members = members,
                )
            }

    suspend fun end(roomId: String): TogetherResult<Unit> =
        client.call("/api/listen/together/end/v2", mapOf("roomId" to roomId))
            .toTogether().also { TogetherLog.add("API end room=${TogetherLog.short(roomId)} -> $it") }
}

/**
 * Whether a returned command means "playing".
 *
 * **The reference implementation's rule, adopted verbatim**: a *start* command (`PLAY`, `GOTO`, `NEXT`,
 * `PREV`) means playing, and so does an explicit `PLAY` status. Only `PAUSE` — as a type or as a status —
 * means paused.
 *
 * The two halves are OR'ed rather than one overriding the other, and that is deliberate. The type is the
 * authoritative statement of intent (the sender chose it from its own player state), while `playStatus` is a
 * field that can be echoed a beat behind. Trusting the status over the type leaves a follower **paused on a
 * track the sender is playing** — the reported "还是无法一起播放" — because the `GOTO` that carries the
 * track change would be read as paused. An earlier version of this function did exactly that.
 *
 * The cost of the OR is the opposite case: a sender that switches tracks while paused reads as playing. That
 * is also what the reference does, it is the safer of the two failures (a room that keeps playing beats a
 * room that never starts), and this client does not send a paused `GOTO` — selecting a track goes through a
 * path that starts it, and an automatic advance happens while playing.
 *
 * Top-level rather than private so the rule can be tested directly, without a network client.
 */
internal fun commandPlaying(type: String, playStatus: String): Boolean {
    val upperType = type.uppercase()
    if (upperType == "PLAY" || upperType == "GOTO" || upperType == "NEXT" || upperType == "PREV") {
        return true
    }
    val upperStatus = playStatus.uppercase()
    return upperStatus == "PLAY" || upperStatus == "PLAYING"
}
