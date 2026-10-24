// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `lx.request` on OkHttp — the only network path a custom source has.
 *
 * ## The shape the doc fixes
 *
 * `lx.request(url, options, callback)` where `options` may carry `method / headers / body / form / formData /
 * timeout`, the callback receives `(err, resp, body)`, `resp` is `{ statusCode, headers, body }`, and the return
 * value is a cancel handle. A source cannot otherwise reach the network, so this function *is* its sandbox
 * boundary: the URL scheme is checked here (http/https only — no `file:`, no `content:`) and nothing else in
 * the bridge opens a socket.
 *
 * ## Why the result is a JSON envelope rather than a thrown error
 *
 * The caller is JavaScript running inside QuickJS. A Kotlin exception thrown through a binding surfaces as an
 * engine failure, not as the `err` argument the API promises, so every outcome — including a bad option, a
 * timeout and a non-2xx status — is returned as `{ok:true,response:…}` or `{ok:false,error:…}` and the JS side
 * turns it into the documented callback. A non-2xx status is **not** an error here: LX sources routinely read a
 * 403 body for an API-specific message, so it is data.
 *
 * ## Cancellation
 *
 * The cancel handle calls [cancel] with the request id, which cancels the OkHttp `Call`. The JS side also marks
 * the id cancelled so a late callback is not delivered.
 */
internal class LxHttpBridge(private val log: LxSourceLog) {

    private val calls = ConcurrentHashMap<Long, Call>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        // Sources call third-party music APIs; a slow one must not hold the JS engine forever, but the limits
        // are generous enough for an ordinary API round trip. A script may ask for its own `timeout` below.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Stops an in-flight request. Safe for an id that has already finished. */
    fun cancel(requestId: Long) {
        calls.remove(requestId)?.cancel()
    }

    suspend fun execute(
        url: String,
        optionsJson: String,
        requestId: Long,
        trace: LxLookupTrace? = null,
    ): String {
        var call: Call? = null
        val startedNanos = System.nanoTime()
        fun millisSinceStart(): Long = (System.nanoTime() - startedNanos) / 1_000_000
        var method = "GET"
        return try {
            val scheme = url.trim().substringBefore(':', "").lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("lx.request 只支持 http/https 地址（收到：${redact(url)}）")
            }
            val options = parseOptions(optionsJson)
            method = options.method
            val request = buildRequest(url.trim(), options)
            call = client.newCall(request)
            if (requestId > 0L) calls[requestId] = call
            options.timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }

            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use { resp ->
                // The body crosses the boundary as raw bytes, not as a string: LX hands scripts `resp.raw` (a
                // Buffer) and `resp.body` (the JSON-parsed object when possible), and a source that needs the
                // binary form (gzip payloads) cannot reconstruct it from a lossily-decoded string.
                val bytes = withContext(Dispatchers.IO) {
                    val buffer = okio.Buffer()
                    resp.body?.source()?.let { source ->
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer, 8 * 1024)
                            if (read == -1L) break
                            total += read
                            check(total <= MAX_RESPONSE_BYTES) {
                                "lx.request: response larger than ${MAX_RESPONSE_BYTES / (1024 * 1024)} MiB"
                            }
                        }
                    }
                    buffer.readByteArray()
                }
                trace?.recordHttpCall(
                    LxLookupTrace.HttpCall(
                        method = method,
                        url = traceRedactUrl(url),
                        status = resp.code,
                        bytes = bytes.size.toLong(),
                        millis = millisSinceStart(),
                        bodySnippet = traceSnippet(
                            String(bytes, 0, minOf(bytes.size, 2_048), Charsets.UTF_8),
                        ),
                        error = null,
                    ),
                )
                JSONObject()
                    .put("ok", true)
                    .put(
                        "response",
                        JSONObject()
                            .put("statusCode", resp.code)
                            .put("statusMessage", resp.message)
                            .put("headers", headersJson(resp.headers))
                            .put("bytes", bytes.size)
                            .put("bodyHex", with(LxHostUtilities) { bytes.toLxHex() }),
                    )
                    .toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: e.javaClass.simpleName
            trace?.recordHttpCall(
                LxLookupTrace.HttpCall(
                    method = method,
                    url = traceRedactUrl(url),
                    status = null,
                    bytes = null,
                    millis = millisSinceStart(),
                    bodySnippet = null,
                    error = message,
                ),
            )
            log.record("lx.request 失败", "${e.javaClass.simpleName}: $message\nurl=${redact(url)}")
            JSONObject().put("ok", false).put("error", message).toString()
        } finally {
            if (requestId > 0L) calls.remove(requestId)
        }
    }

    // ---------------------------------------------------------------- options → Request

    private class Options(
        val method: String,
        val headers: Map<String, String>,
        val body: RequestBody?,
        val timeoutMs: Long?,
    )

    private fun parseOptions(optionsJson: String): Options {
        val json = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
        val headers = LinkedHashMap<String, String>()
        json.optJSONObject("headers")?.let { headerJson ->
            val names = headerJson.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = headerJson.opt(name)
                if (value == null || value == JSONObject.NULL) continue
                headers[name] = value.toString()
            }
        }
        // Sources are written against LX's own client and some endpoints key on the user agent; set it only
        // when the script did not, so an explicit header always wins.
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = USER_AGENT
        }
        val method = json.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val timeoutMs = json.optLong("timeout", 0L).takeIf { it in 1..MAX_TIMEOUT_MS }

        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val body = when {
            json.has("form") && json.optJSONObject("form") != null -> formBody(json.optJSONObject("form")!!)
            json.has("formData") && json.optJSONObject("formData") != null -> formDataBody(json.optJSONObject("formData")!!)
            json.has("body") -> bodyOf(json.opt("body"), contentType)
            else -> null
        }
        return Options(method = method, headers = headers, body = body, timeoutMs = timeoutMs)
    }

    private fun buildRequest(url: String, options: Options): Request {
        val builder = Request.Builder().url(url)
        options.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = when {
            options.method in METHODS_WITHOUT_BODY -> {
                // OkHttp rejects a body on GET/HEAD, and LX sources do not send one there; an explicit body
                // is a script bug worth naming rather than silently dropping.
                if (options.body != null) {
                    throw IllegalArgumentException("lx.request: ${options.method} 请求不能带 body")
                }
                null
            }

            options.method in METHODS_REQUIRING_BODY -> options.body ?: EMPTY_BODY
            else -> options.body
        }
        builder.method(options.method, body)
        return builder.build()
    }

    private fun formBody(form: JSONObject): RequestBody {
        val builder = FormBody.Builder()
        val names = form.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = form.opt(name)
            if (value == null || value == JSONObject.NULL) continue
            builder.add(name, value.toString())
        }
        return builder.build()
    }

    /**
     * `formData` is multipart. A string is a normal field; `{type:'Buffer', data:[…]}` is a buffer, which is
     * how a Buffer survives the JSON boundary — enough for the small uploads LX sources make. Anything else is
     * named as unsupported instead of being stringified into a corrupt part.
     */
    private fun formDataBody(formData: JSONObject): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        val names = formData.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = formData.opt(name)
            if (value == null || value == JSONObject.NULL) continue
            when {
                value is String -> builder.addFormDataPart(name, value)
                value is JSONObject -> {
                    val bytes = bufferBytes(value)
                        ?: throw IllegalArgumentException("lx.request: formData 字段 $name 不是字符串或 Buffer")
                    builder.addFormDataPart(
                        name,
                        value.optString("filename").takeIf { it.isNotBlank() } ?: name,
                        bytes.toRequestBody(OCTET_STREAM),
                    )
                }

                else -> throw IllegalArgumentException("lx.request: formData 字段 $name 不是字符串或 Buffer")
            }
        }
        return builder.build()
    }

    private fun bodyOf(value: Any?, contentTypeHeader: String?): RequestBody? {
        if (value == null || value == JSONObject.NULL) return null
        val mediaType = contentTypeHeader?.toMediaTypeOrNull()
        return when (value) {
            is String -> value.toRequestBody(mediaType)
            is JSONObject -> bufferBytes(value)?.toRequestBody(mediaType ?: OCTET_STREAM)
                ?: value.toString().toRequestBody(mediaType ?: JSON_MEDIA_TYPE)
            is JSONArray -> value.toString().toRequestBody(mediaType ?: JSON_MEDIA_TYPE)
            is Number, is Boolean -> value.toString().toRequestBody(mediaType ?: JSON_MEDIA_TYPE)
            else -> throw IllegalArgumentException("lx.request: body 类型不受支持")
        }
    }

    /** `{type:'Buffer', data:[…]}` — the JSON form a Buffer takes when it crosses the JS boundary. */
    private fun bufferBytes(value: JSONObject): ByteArray? {
        if (value.optString("type") != "Buffer") return null
        val data = value.optJSONArray("data") ?: return null
        val bytes = ByteArray(data.length())
        for (i in 0 until data.length()) {
            val number = data.optInt(i)
            require(number in 0..255) { "lx.request: Buffer 内容越界" }
            bytes[i] = number.toByte()
        }
        return bytes
    }

    private fun headersJson(headers: Headers): JSONObject {
        val json = JSONObject()
        headers.names().forEach { name ->
            json.put(name, headers.values(name).joinToString(", "))
        }
        return json
    }

    /** Logs and error messages keep only the origin: a source's URL usually carries an API key or a signature. */
    private fun redact(url: String): String {
        val trimmed = url.trim()
        return trimmed.substringBefore('?').take(MAX_LOGGED_URL_LENGTH)
    }

    private companion object {
        /**
         * LX's own client user agent. Some music APIs answer differently (or refuse) for an unknown client, and
         * a source is written against the LX host, so the app presents itself the way those APIs expect.
         */
        const val USER_AGENT = "lx-music-mobile/2.11.0"

        const val MAX_TIMEOUT_MS = 60_000L
        const val MAX_LOGGED_URL_LENGTH = 120

        /** A music-API response is small; the cap keeps a hostile or wrong URL from exhausting the heap. */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        val METHODS_WITHOUT_BODY = setOf("GET", "HEAD")
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}
