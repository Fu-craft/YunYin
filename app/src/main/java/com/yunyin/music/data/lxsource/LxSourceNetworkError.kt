package com.yunyin.music.data.lxsource

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Turns a download exception into a line that names the failing network layer.
 *
 * A bare "网络错误" is not actionable: a DNS-poisoned domain, a blocked route, a TLS handshake rejected by a middlebox
 * and a typo all look identical to the user. The classification below distinguishes them from the exception chain,
 * and appends the exception class/message (short) plus OkHttp's connection attempt count and elapsed time — all
 * attempts failing after N seconds is a different diagnosis from a single immediate failure.
 */
object LxSourceNetworkError {

    /** [message] is the sheet line; [detail] is what `last-error.txt` keeps (class, message, attempts, elapsed). */
    data class Described(val message: String, val detail: String)

    fun describe(
        error: Throwable,
        host: String?,
        attempts: Int = 0,
        elapsedMs: Long = 0L,
    ): Described {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val reason = when {
            chain.any { it is UnknownHostException } -> "无法解析域名（${host ?: "未知主机"}）"
            chain.any { it is SocketTimeoutException } -> "连接超时"
            // OkHttp's call-timeout (and an interrupted blocking read) surfaces as InterruptedIOException with
            // "timeout", not SocketTimeoutException — observed on raw.githubusercontent.com stalling mid-body.
            chain.any { it is java.io.InterruptedIOException } -> "连接超时（传输中断）"
            chain.any { it is SSLHandshakeException } -> "TLS 失败（证书/握手）"
            chain.any { it is SSLException } -> "TLS 失败（${chain.first { c -> c is SSLException }.javaClass.simpleName}）"
            chain.any { it is ConnectException } -> "连接被拒绝"
            chain.any { it is SocketException && isReset(it.message) } -> "连接被重置"
            chain.any { it is SocketException && isBrokenPipe(it.message) } -> "连接中断"
            chain.any { it.message?.contains("Too many follow-up", ignoreCase = true) == true } -> "重定向失败"
            else -> error.javaClass.simpleName
        }
        val exception = listOfNotNull(error.javaClass.simpleName, error.message?.take(120)).joinToString(": ")
        val stats = buildList {
            if (attempts > 0) add("尝试 $attempts 次")
            // Sub-second elapsed is noise on a DNS failure; only report it when it says something.
            if (elapsedMs >= 1_000) add("耗时 ${"%.1f".format(java.util.Locale.ROOT, elapsedMs / 1000.0)} 秒")
        }.joinToString("，")
        val message = buildString {
            append("网络错误：").append(reason)
            append("（").append(exception).append("）")
            if (stats.isNotEmpty()) append("（").append(stats).append("）")
        }
        val detail = buildString {
            append("host=").append(host ?: "-").append('\n')
            append("error=").append(exception).append('\n')
            append("attempts=").append(attempts).append(" elapsedMs=").append(elapsedMs).append('\n')
            chain.drop(1).forEach { cause ->
                append("cause=").append(cause.javaClass.simpleName).append(": ")
                    .append(cause.message).append('\n')
            }
        }
        return Described(message = message, detail = detail.trimEnd())
    }

    private fun isReset(message: String?): Boolean =
        message?.contains("reset", ignoreCase = true) == true

    private fun isBrokenPipe(message: String?): Boolean =
        message?.contains("broken pipe", ignoreCase = true) == true ||
            message?.contains("connection abort", ignoreCase = true) == true
}
