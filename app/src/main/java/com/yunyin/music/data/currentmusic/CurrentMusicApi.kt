// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.currentmusic

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
