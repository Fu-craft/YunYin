package com.yunyin.music.data.together.ltw

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP access to the listen-together service.
 *
 * Plain request/response, no state: what the room *is* lives in [LtwTransport]. Every endpoint was exercised
 * against the deployed worker before this was written (`create` → `join` → `control PLAY` → `state` returned
 * `playing` with an advanced `expectedPositionMs`, and a `PAUSE` returned it to `paused`), so the shapes here
 * are measured rather than assumed.
 *
 * Errors carry the service's own `error` string, because the caller has to tell three of them apart: a closed
 * room (410 — stop the session), an offline controller (a listener's request is refused, which is a *state*
 * to explain rather than a fault), and everything else.
 */
class LtwApi(private val client: OkHttpClient = defaultClient()) {

    /** `POST /api/rooms` — opens a room, seeded with the controller's current track and queue. */
    suspend fun create(
        base: String,
        userUuid: String,
        nickname: String,
        seed: LtwSeed,
    ): Result<LtwMembership> = call(
        base, "POST", "/api/rooms", body = JSONObject()
            .put("userUuid", userUuid)
            .put("nickname", nickname)
            .put("initialSnapshot", seed.toSnapshotJson()),
    ).mapCatching { json -> membership(json) }

    /** `POST /api/rooms/:id/join` — joins with the room's `joinSecret`, or rejoins with this member's own. */
    suspend fun join(
        base: String,
        roomId: String,
        userUuid: String,
        nickname: String,
        joinSecret: String?,
        memberSecret: String?,
    ): Result<LtwMembership> {
        val body = JSONObject().put("userUuid", userUuid).put("nickname", nickname)
        joinSecret?.takeIf { it.isNotBlank() }?.let { body.put("joinSecret", it) }
        memberSecret?.takeIf { it.isNotBlank() }?.let { body.put("memberSecret", it) }
        return call(base, "POST", "/api/rooms/$roomId/join", body = body)
            .mapCatching { membership(it) }
    }

    /** `GET /api/rooms/:id/state` — the authoritative snapshot plus the server's clock. */
    suspend fun state(base: String, roomId: String, token: String): Result<LtwSnapshot> =
        call(base, "GET", "/api/rooms/$roomId/state", token = token).mapCatching { json ->
            val state = LtwJson.roomState(json.optJSONObject("state"))
                ?: throw LtwProtocolException("房间状态无法解析", 200, null)
            LtwSnapshot(
                state = state,
                expectedPositionMs = json.optLong("expectedPositionMs"),
                serverNowMs = json.optLong("serverNowMs"),
            )
        }

    /**
     * `POST /api/rooms/:id/control` — a control event from the controller, or a request from a listener.
     *
     * A refusal comes back as a failure with the service's own reason (`controller offline`, `member control
     * target mismatch`, …): those are states the UI names, not faults to hide.
     */
    suspend fun control(
        base: String,
        roomId: String,
        token: String,
        event: JSONObject,
    ): Result<Unit> = call(base, "POST", "/api/rooms/$roomId/control", token = token, body = event)
        .mapCatching { }

    /** `POST /api/rooms/:id/leave` — this member leaves. A controller leaving ends the room. */
    suspend fun leave(base: String, roomId: String, token: String): Result<Unit> {
        val result = call(base, "POST", "/api/rooms/$roomId/leave", token = token)
        // Leaving must never read as a failure to the user: the local session is already gone, and the
        // room will expire on its own.
        return result.mapCatching { }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun call(
        base: String,
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/') + path
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            else -> builder.post((body?.toString() ?: "{}").toRequestBody(JSON))
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (response.isSuccessful && json?.optBoolean("ok", true) != false) {
                    return@withContext Result.success(json ?: JSONObject())
                }
                val errorCode = json?.optString("error")?.ifBlank { null }
                val message = when {
                    errorCode == null -> "服务响应无法解析"
                    errorCode == "controller offline" -> "房主暂时离线，等对方回来再操作"
                    errorCode == "room closed" -> "这个一起听已结束"
                    errorCode == "member control target mismatch" -> "这条操作已过期，请重试"
                    errorCode == "member control target unavailable" -> "房间里没有这首歌"
                    errorCode == "join_secret_required" -> "邀请信息不完整，请粘贴对方分享的邀请"
                    errorCode == "member_secret_required" -> "这个房间的成员信息已失效，请重新加入"
                    else -> errorCode
                }
                Result.failure(LtwProtocolException(message, response.code, errorCode))            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun membership(json: JSONObject): LtwMembership {
        val roomId = json.optString("roomId")
        val token = json.optString("token")
        val state = LtwJson.roomState(json.optJSONObject("state"))
        if (roomId.isBlank() || token.isBlank() || state == null) {
            throw LtwProtocolException("房间信息不完整", 200, null)
        }
        return LtwMembership(
            roomId = roomId,
            userUuid = json.optString("userUuid").ifBlank { json.optString("userId") },
            nickname = json.optString("nickname"),
            role = json.optString("role", "listener"),
            token = token,
            memberSecret = json.optString("memberSecret").ifBlank { null },
            joinSecret = json.optString("joinSecret").ifBlank { null },
            state = state,
        )
    }

    /**
     * The controller's seeding payload.
     *
     * The service **requires** a shareable current track (`initial snapshot requires a shareable current
     * track`), so a room cannot be created from silence — which is the correct rule, since a room whose only
     * track is missing has nothing for the other member to hear.
     */
    data class LtwSeed(
        val tracks: List<LtwTrack>,
        val currentIndex: Int,
        val positionMs: Long,
        val playing: Boolean,
        val repeatMode: Int = 0,
        val shuffleEnabled: Boolean = false,
    ) {
        fun toSnapshotJson(): JSONObject = JSONObject()
            .put("track", trackJson(tracks.getOrNull(currentIndex)))
            .put("queue", JSONArray().also { arr -> tracks.forEach { arr.put(trackJson(it)) } })
            .put("currentIndex", currentIndex)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("isPlaying", playing)
            .put("repeatMode", repeatMode)
            .put("shuffleEnabled", shuffleEnabled)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "YunYin-Android"

        fun trackJson(track: LtwTrack?): JSONObject? = track?.let {
            JSONObject()
                .put("stableKey", it.stableKey)
                .put("channelId", it.channelId)
                .put("audioId", it.audioId)
                .put("name", it.name)
                .put("artist", it.artist)
                .put("album", it.album ?: "")
                .put("durationMs", it.durationMs)
                .put("coverUrl", it.coverUrl ?: "")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/** A refusal or malformed response carrying the service's own reason and status. */
class LtwProtocolException(
    override val message: String,
    val httpCode: Int,
    val errorCode: String?,
) : Exception(message) {
    val failure: LtwFailure get() = LtwFailure(message, httpCode, errorCode)
}
