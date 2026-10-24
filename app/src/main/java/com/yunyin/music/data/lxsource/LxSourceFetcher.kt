// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** What a URL import produced. Failures carry the exact user-facing line requested for that cause. */
sealed interface LxSourceFetchResult {

    data class Ok(val text: String, val bytes: Int, val finalUrl: String) : LxSourceFetchResult

    data class Failure(val message: String, val detail: String? = null) : LxSourceFetchResult
}

/** Downloads a script from a URL. An interface so the import pipeline can be tested without a network. */
interface LxSourceFetcher {
    suspend fun fetch(url: String): LxSourceFetchResult
}

/**
 * Content checks on a downloaded body.
 *
 * `.js` files are served with every imaginable `Content-Type` (or none), so the content type is not evidence. What
 * *is* evidence is the body itself: an HTML error page (a GitHub 404 page, a captive portal) starts with a tag, and
 * binary garbage is not a script. Both are refused with a specific message instead of being handed to the engine,
 * where they would fail as a confusing syntax error.
 */
object LxSourceContent {

    /** Same cap as a local file, enforced while streaming by [OkHttpLxSourceFetcher]. */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun overSizeMessage(bytes: Int): String? =
        if (bytes > MAX_BYTES) "内容太大（超过 4MB）" else null

    /** Null when the body looks like a script; otherwise the specific refusal. */
    fun sniffFailure(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "返回的不是脚本（内容为空）"
        val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).trimStart()
        when {
            prefix.isEmpty() -> return "返回的不是脚本（内容为空）"
            prefix.startsWith("<") -> return "返回的不是脚本（像是网页）"
            prefix.contains("<!doctype html", ignoreCase = true) -> return "返回的不是脚本（像是网页）"
            prefix.contains("<html", ignoreCase = true) -> return "返回的不是脚本（像是网页）"
        }
        // A text script has very few control bytes; a binary payload (image/gzip served by mistake) is full of them.
        val control = bytes.take(minOf(bytes.size, 4096)).count { byte ->
            val value = byte.toInt() and 0xff
            value < 0x09 || (value in 0x0e..0x1f)
        }
        if (control * 20 > minOf(bytes.size, 4096)) return "返回的不是脚本（内容不是文本）"
        return null
    }

    /** HTTP status → the message list's specific lines; null for success. */
    fun statusFailure(status: Int): String? = when {
        status in 200..299 -> null
        status == 404 -> "HTTP 404：链接不存在"
        status in 500..599 -> "HTTP 5xx：服务器错误（$status）"
        else -> "HTTP $status：请求失败"
    }
}

/**
 * Downloads a source script with the policy in [LxSourceUrlPolicy] plus a streaming size cap.
 *
 * Redirects are followed (GitHub raw redirects to a CDN host) but **every hop is re-checked** by
 * [SsrfGuardInterceptor], so a redirect cannot smuggle the request into the private network. The body is read
 * chunk-by-chunk and abandoned as soon as it exceeds [LxSourceContent.MAX_BYTES], so an endless response cannot eat
 * memory.
 */
class OkHttpLxSourceFetcher(
    private val log: LxSourceLog? = null,
    client: OkHttpClient = defaultClient(),
) : LxSourceFetcher {

    private val client: OkHttpClient = client.newBuilder()
        .addInterceptor(SsrfGuardInterceptor())
        .build()

    override suspend fun fetch(url: String): LxSourceFetchResult {
        val verdict = LxSourceUrlPolicy.validate(url)
        if (verdict is LxSourceUrlPolicy.Verdict.Rejected) {
            return LxSourceFetchResult.Failure(verdict.message, "policy rejected: ${LxSourceUrlPolicy.redact(url)}")
        }
        val allowed = verdict as LxSourceUrlPolicy.Verdict.Allowed
        val resolution = try {
            resolveGuard(allowed.host)
        } catch (t: Throwable) {
            record(url, "dns ${t.javaClass.simpleName}: ${t.message}")
            return LxSourceFetchResult.Failure("网络错误", "dns ${t.javaClass.simpleName}: ${t.message}")
        }
        if (resolution != null) {
            record(url, resolution)
            return LxSourceFetchResult.Failure("不允许内网地址", resolution)
        }
        // Counts connect attempts so a "timed out after 3 attempts" line is possible: Android's stack may try
        // several A/AAAA records, and all-of-them-failed is a different diagnosis from one refusal.
        val stats = CallStats()
        val started = System.nanoTime()
        return try {
            runInterruptible(Dispatchers.IO) {
                // A per-call EventListener observes attempts; runInterruptible makes the blocking call actually
                // cancellable, so the manager's total deadline can abort a stalled attempt instead of overrunning it.
                client.newBuilder().eventListener(stats).build()
                    .newCall(Request.Builder().url(allowed.url).header("User-Agent", USER_AGENT).build())
                    .execute().use { response ->
                        val statusFailure = LxSourceContent.statusFailure(response.code)
                        if (statusFailure != null) {
                            record(url, "status=${response.code}")
                            return@use LxSourceFetchResult.Failure(statusFailure, "https status ${response.code}")
                        }
                        response.header("Content-Length")?.toLongOrNull()?.let { length ->
                            if (length > LxSourceContent.MAX_BYTES) {
                                record(url, "content-length=$length")
                                return@use LxSourceFetchResult.Failure("内容太大（超过 4MB）", "content-length=$length")
                            }
                        }
                        val bytes = readBounded(response)
                            ?: return@use LxSourceFetchResult.Failure("内容太大（超过 4MB）", "stream over cap")
                        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                        val sniff = LxSourceContent.sniffFailure(bytes)
                        if (sniff != null) {
                            record(url, "sniff failed bytes=${bytes.size} head=${traceSnippet(text)}")
                            return@use LxSourceFetchResult.Failure(sniff, "sniff failed: ${traceSnippet(text)}")
                        }
                        LxSourceFetchResult.Ok(text = text, bytes = bytes.size, finalUrl = response.request.url.toString())
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val message = e.message.orEmpty()
            if (message.startsWith(BLOCKED_PREFIX)) {
                record(url, message)
                LxSourceFetchResult.Failure("不允许内网地址", message)
            } else {
                val described = LxSourceNetworkError.describe(e, allowed.host, stats.connects, elapsed)
                record(url, described.detail)
                LxSourceFetchResult.Failure(described.message, described.detail)
            }
        } catch (t: Throwable) {
            // A fetch is a UI boundary: a TLS/native `Error` must not escape and kill the app mid-tap.
            val described = LxSourceNetworkError.describe(t, allowed.host, stats.connects, 0L)
            record(url, described.detail)
            LxSourceFetchResult.Failure(described.message, described.detail)
        }
    }

    /** OkHttp EventListener that counts connection attempts for the failure line (cheapest useful diagnostic). */
    private class CallStats : EventListener() {
        @Volatile
        var connects: Int = 0
            private set

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects++
        }
    }

    /** Reads at most MAX+1 bytes; null when the response exceeds the cap. */
    private fun readBounded(response: Response): ByteArray? {
        val body = response.body ?: return ByteArray(0)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > LxSourceContent.MAX_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun resolveGuard(host: String): String? = try {
        val addresses = InetAddress.getAllByName(host)
        if (addresses.any { LxSourceUrlPolicy.isBlockedAddress(it) }) {
            "resolved to ${addresses.joinToString { it.hostAddress.orEmpty() }}"
        } else {
            null
        }
    } catch (e: UnknownHostException) {
        // Not a policy failure; the request below will report it as a network error.
        null
    }

    private fun record(url: String, detail: String) {
        runCatching { log?.record("从链接导入失败", "url=${LxSourceUrlPolicy.redact(url)}\n$detail") }
    }

    /** Re-checks every redirect hop: scheme, credentials, literal/private host and its DNS resolution. */
    private inner class SsrfGuardInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (!response.isRedirect) return response
            val location = response.header("Location") ?: return response
            val target = response.request.url.resolve(location) ?: return response
            val verdict = LxSourceUrlPolicy.validate(target.toString())
            val blocked = when (verdict) {
                is LxSourceUrlPolicy.Verdict.Rejected -> verdict.message
                is LxSourceUrlPolicy.Verdict.Allowed -> resolveGuard(verdict.host)
            }
            if (blocked != null) {
                response.close()
                throw IOException("$BLOCKED_PREFIX$blocked")
            }
            return response
        }
    }

    companion object {
        private const val USER_AGENT = "YunYin/1.0 (LX custom source import)"
        private const val BLOCKED_PREFIX = "blocked: "

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
