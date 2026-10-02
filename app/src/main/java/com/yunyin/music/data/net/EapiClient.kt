package com.yunyin.music.data.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * NetEase's eapi endpoint: one POST, AES-encrypted, signed with the caller's own login cookie.
 *
 * Exists because listen-together is **not** available through the API proxy this app otherwise uses — see
 * [Eapi]. Kept deliberately small: it does the transport, and the operation names and payloads live in the
 * caller that knows the protocol.
 *
 * Errors are returned rather than thrown, matching the rest of the networking layer's style, because every
 * caller here has to distinguish "the room is gone" from "the network is down".
 */
class EapiClient(
    private val cookieProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
) {

    /** One call. Returns the parsed body, or a failure message. */
    suspend fun call(path: String, data: Map<String, Any?> = emptyMap()): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            val cookie = cookieProvider()
            if (cookie.isBlank()) return@withContext Result.failure(IllegalStateException("请先登录"))
            val (params, cookieHeader) = Eapi.sign(path, data, cookie)
            val request = Request.Builder()
                .url(Eapi.url(path))
                .header("User-Agent", Eapi.UA)
                .header("Cookie", cookieHeader)
                .post(FormBody.Builder().add("params", params).build())
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("HTTP ${response.code}"),
                        )
                    }
                    val json = runCatching { JSONObject(body) }.getOrNull()
                        ?: return@withContext Result.failure(
                            IllegalStateException("响应无法解析"),
                        )
                    // 200 is success; the protocol also accepts a few others as "answered", but for our
                    // purposes anything else is an error the caller must see — `301` in particular means the
                    // session is not signed in, which is worth surfacing plainly rather than as an empty room.
                    val code = json.optInt("code", 200)
                    if (code == 200 || code == 201) {
                        Result.success(json)
                    } else {
                        val message = json.optString("message").ifBlank { json.optString("msg") }
                        Result.failure(IllegalStateException(message.ifBlank { "code $code" }))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
