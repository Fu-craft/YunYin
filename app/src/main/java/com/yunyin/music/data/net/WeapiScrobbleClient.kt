package com.yunyin.music.data.net

import com.yunyin.music.core.NetResult
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct NetEase `weapi` play reporting (`/weapi/feedback/weblog`).
 *
 * The self-hosted API is a stub for writes, so this is the only honest transport for 听歌记录上报. It sends the
 * app's real `MUSIC_U` session cookie to `music.163.com`, keeping `__csrf` in the form when the cookie carries it.
 *
 * Failure is a value: the caller logs it and gives up (never falls back to the stub — that would fabricate success).
 */
class WeapiScrobbleClient(
    private val http: OkHttpClient = defaultClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    data class Outcome(
        val http: Int,
        val body: String,
        val ok: Boolean,
        val reason: String? = null,
    )

    /**
     * Posts one play log. [cookie] must be the real MUSIC_U session; anonymous sessions are refused by the caller
     * before reaching here.
     */
    suspend fun send(cookie: String, songId: Long, sourceId: Long, seconds: Int): Outcome = runCatching {
        val payload = JSONObject()
            .put(
                "logs",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("action", "play")
                        .put(
                            "json",
                            JSONObject()
                                .put("download", 0)
                                .put("end", "playend")
                                .put("id", songId)
                                .put("sourceId", sourceId)
                                .put("time", seconds)
                                .put("type", "song")
                                .put("wifi", 0),
                        ),
                ),
            ).toString()

        val secret = WeapiCrypto.randomSecretKey()
        val encrypted = WeapiCrypto.encrypt(payload, secret)
        val csrf = csrfFrom(cookie)
        val form = FormBody.Builder()
            .add("params", encrypted.params)
            .add("encSecKey", encrypted.encSecKey)
            .apply { if (csrf != null) add("csrf_token", csrf) }
            .build()

        val builder = Request.Builder()
            .url(endpoint)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)

        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
            Outcome(
                http = response.code,
                body = text.take(400),
                ok = response.isSuccessful && code == 200,
                reason = if (response.isSuccessful && code == 200) null else "code=$code",
            )
        }
    }.getOrElse { error ->
        Outcome(http = 0, body = "", ok = false, reason = error.message ?: error.javaClass.simpleName)
    }

    /**
     * One-shot check whether the configured self-hosted API behaves like a stub for `/scrobble`.
     *
     * The stub answers `{"code":200,"data":"success"}` even for `id=0`. This is only a diagnostic: the direct weapi
     * path does not depend on its answer.
     */
    suspend fun selfHostedIsStub(baseUrl: String): Boolean = runCatching {
        if (baseUrl.isBlank()) return@runCatching false
        val url = baseUrl.trimEnd('/') + "/scrobble?id=0&sourceid=0&time=0&timestamp=" + System.currentTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
            val data = runCatching { JSONObject(text).optString("data") }.getOrDefault("")
            response.isSuccessful && code == 200 && data == "success"
        }
    }.getOrDefault(false)

    private fun csrfFrom(cookie: String): String? =
        Regex("(?:^|;\\s*)__csrf=([^;]+)").find(cookie)?.groupValues?.get(1)

    companion object {
        const val DEFAULT_ENDPOINT = "https://music.163.com/weapi/feedback/weblog"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
