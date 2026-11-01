// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.currentmusic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CurrentMusic 站内账号客户端。
 *
 * 接口与字段全部按官方文档（`music.20110208.xyz/api-docs`）实现，不做推测：
 *
 * | 方法 | 路径 | 说明 |
 * |---|---|---|
 * | POST | `/auth/phone/code` | 手机注册/登录验证码（**由站内服务器下发**，不是网易） |
 * | POST | `/auth/email/code` | 邮箱验证码 |
 * | POST | `/auth/register` | 注册，返回 token + user |
 * | POST | `/auth/login` | 登录，返回 token + user |
 * | GET  | `/auth/me` | 当前账号 |
 *
 * 认证头是 `Authorization: Bearer <token>`（文档“认证、错误与请求约定”）。
 * 房间（一起听）接口在 `/rooms*`，后续在同一客户端上补。
 */
class CurrentMusicApi(
    private val client: OkHttpClient = defaultClient,
    baseUrl: String = DEFAULT_BASE_URL,
) {

    private val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

    data class Account(val id: Long, val nickname: String, val username: String)
    data class Session(val token: String, val account: Account)

    suspend fun sendPhoneCode(phone: String): Result<Unit> =
        post("/auth/phone/code", JSONObject().put("phone", phone)).map { }

    suspend fun sendEmailCode(email: String): Result<Unit> =
        post("/auth/email/code", JSONObject().put("email", email)).map { }

    suspend fun register(
        username: String,
        password: String,
        nickname: String,
        contact: String,
        code: String,
        phone: Boolean,
        device: String,
    ): Result<Session> = post(
        "/auth/register",
        JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("nickname", nickname.trim().ifBlank { username.trim() })
            .put(if (phone) "phone" else "email", contact.trim())
            .put(if (phone) "phoneCode" else "emailCode", code.trim())
            .put("device", device)
            .put("platform", "app"),
    ).map { it.toSession() }

    suspend fun login(
        username: String,
        password: String,
        device: String,
    ): Result<Session> = post(
        "/auth/login",
        JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("device", device)
            .put("platform", "app"),
    ).map { it.toSession() }

    suspend fun me(token: String): Result<Account> = get("/auth/me", token).map { body ->
        (body.optJSONObject("user") ?: body).toAccount()
    }

    // ---------------------------------------------------------------- rooms（一起听）

    data class Room(
        val id: String,
        val name: String,
        val code: String,
        val members: Int,
        val playing: Boolean,
        val public: Boolean,
    )

    suspend fun rooms(token: String, query: String = "", offset: Int = 0): Result<List<Room>> =
        get("/rooms?query=$query&limit=20&offset=$offset", token).map { body ->
            val array = body.optJSONArray("rooms") ?: body.optJSONArray("items") ?: org.json.JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                item.toRoom()
            }
        }

    suspend fun createRoom(token: String, name: String, password: String = "", public: Boolean = true): Result<Room> =
        post(
            "/rooms",
            JSONObject()
                .put("name", name)
                .put("password", password)
                .put("isPublic", public)
                .put("freeMode", true)
                .put("needApproval", false),
            token,
        ).map { it.optJSONObject("room")?.toRoom() ?: it.toRoom() }

    suspend fun joinRoom(token: String, id: String, password: String = ""): Result<Unit> =
        post("/rooms/$id/join", JSONObject().put("password", password), token).map { }

    suspend fun roomAction(token: String, id: String, action: String): Result<Unit> =
        post("/rooms/$id/$action", JSONObject(), token).map { }

    /** 时钟对齐：返回服务器当前毫秒时间戳。 */
    suspend fun syncRoom(token: String, id: String): Result<Long> =
        post("/rooms/$id/sync", JSONObject(), token).map { it.optLong("serverNow") }

    data class RoomDetail(
        val nowPlayingName: String,
        val nowPlayingArtist: String,
        val queue: List<String>,
    )

    /** 把一首歌加入房间队列：POST /rooms/{id}/queue { meta }（meta 为歌曲元数据）。 */
    suspend fun addToQueue(token: String, id: String, meta: JSONObject): Result<Unit> =
        post("/rooms/$id/queue", JSONObject().put("meta", meta), token).map { }

    /**
     * 房间事件流（SSE）：`GET /rooms/{id}/events`。
     *
     * 服务端按 `data: {...}` 逐行推送；这里只把每行的 JSON 交给调用方，由它决定刷新哪些状态，
     * 免得把服务端的事件名硬编码进网络层。
     */
    fun roomEvents(token: String, id: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val request = Request.Builder()
                .url("$base/rooms/$id/events")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("SSE HTTP ${response.code}")
                val source = response.body?.source() ?: error("SSE 无响应体")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) emit(line.removePrefix("data:").trim())
                }
            }
        }.flowOn(Dispatchers.IO)

    suspend fun roomDetail(token: String, id: String): Result<RoomDetail> =
        get("/rooms/$id", token).map { body ->
            val room = body.optJSONObject("room") ?: body
            val current = room.optJSONObject("current")
                ?: room.optJSONObject("nowPlaying")
                ?: room.optJSONObject("playing")
            val queueArray = room.optJSONArray("queue")
                ?: room.optJSONArray("tracks")
                ?: org.json.JSONArray()
            RoomDetail(
                nowPlayingName = current?.optString("name").orEmpty(),
                nowPlayingArtist = current?.optString("artists").orEmpty(),
                queue = (0 until queueArray.length()).mapNotNull { index ->
                    val item = queueArray.optJSONObject(index) ?: return@mapNotNull null
                    val track = item.optJSONObject("meta") ?: item
                    track.optString("name").takeIf { it.isNotBlank() }
                },
            )
        }
    private fun JSONObject.toRoom(): Room = Room(
        id = optString("id").ifBlank { optString("roomId") },
        name = optString("name"),
        code = optString("code"),
        members = optInt("members").takeIf { it > 0 } ?: optJSONArray("members")?.length() ?: 0,
        playing = optBoolean("playing"),
        public = optBoolean("isPublic", true),
    )

    // ---------------------------------------------------------------- transport

    private suspend fun post(path: String, body: JSONObject, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(base + path)
                    .post(body.toString().toRequestBody(JSON))
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code} ${text.take(160)}")
                    JSONObject(text)
                }
            }
        }

    private suspend fun get(path: String, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(base + path)
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code} ${text.take(160)}")
                    JSONObject(text)
                }
            }
        }

    private fun JSONObject.toSession(): Session {
        val token = optString("token")
        require(token.isNotBlank()) { "登录响应缺少 token" }
        return Session(token, optJSONObject("user")?.toAccount() ?: Account(0, "", ""))
    }

    private fun JSONObject.toAccount(): Account = Account(
        id = optLong("id"),
        nickname = optString("nickname"),
        username = optString("username"),
    )

    companion object {
        /** 文档给出的默认服务器。 */
        const val DEFAULT_BASE_URL = "https://music.20110208.xyz/cm"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
