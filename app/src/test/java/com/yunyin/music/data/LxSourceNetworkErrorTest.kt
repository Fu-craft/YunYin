// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxSourceNetworkError
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Failure-layer classification for URL downloads.
 *
 * The user's report was a bare "网络错误", which cannot distinguish DNS poisoning from a reset from a typo. Each
 * layer now gets its own line, and the exception class/message plus attempt count are part of it.
 */
class LxSourceNetworkErrorTest {

    private fun describe(error: Throwable, attempts: Int = 0, elapsedMs: Long = 0L) =
        LxSourceNetworkError.describe(error, "raw.githubusercontent.com", attempts, elapsedMs)

    @Test
    fun `a DNS failure names the host and the exception`() {
        val described = describe(UnknownHostException("Unable to resolve host \"raw.githubusercontent.com\""))
        assertTrue(described.message, described.message.startsWith("网络错误：无法解析域名（raw.githubusercontent.com）"))
        assertTrue(described.message, described.message.contains("UnknownHostException"))
        assertTrue(described.detail, described.detail.contains("host=raw.githubusercontent.com"))
    }

    @Test
    fun `a timeout reports attempts and elapsed seconds`() {
        val described = describe(SocketTimeoutException("timeout"), attempts = 3, elapsedMs = 12_345)
        assertTrue(described.message, described.message.startsWith("网络错误：连接超时"))
        assertTrue(described.message, described.message.contains("尝试 3 次"))
        assertTrue(described.message, described.message.contains("耗时 12.3 秒"))
    }

    @Test
    fun `an interrupted transfer timeout reads as a timeout, not as a class name`() {
        // OkHttp call-timeout and interrupted blocking reads surface as InterruptedIOException, not SocketTimeout;
        // this is what raw.githubusercontent.com's mid-body stall produces in practice.
        val described = describe(java.io.InterruptedIOException("timeout"), attempts = 2, elapsedMs = 30_001)
        assertTrue(described.message, described.message.startsWith("网络错误：连接超时（传输中断）"))
        assertTrue(described.message, described.message.contains("尝试 2 次"))
    }

    @Test
    fun `a DNS failure does not claim an elapsed time`() {
        val described = describe(UnknownHostException("unable to resolve"), attempts = 0, elapsedMs = 2)
        assertTrue(described.message, !described.message.contains("耗时"))
    }

    @Test
    fun `a reset is distinguished from a TLS failure`() {
        val reset = describe(SocketException("Connection reset"))
        assertTrue(reset.message, reset.message.startsWith("网络错误：连接被重置"))
        val tls = describe(SSLHandshakeException("handshake failed"))
        assertTrue(tls.message, tls.message.startsWith("网络错误：TLS 失败（证书/握手）"))
    }

    @Test
    fun `certificate-chain SSL errors still read as TLS`() {
        val described = describe(javax.net.ssl.SSLPeerUnverifiedException("no peer certificate"))
        assertTrue(described.message, described.message.startsWith("网络错误：TLS 失败"))
    }

    @Test
    fun `connection refused and redirect loops have their own reasons`() {
        assertTrue(describe(ConnectException("Connection refused")).message.startsWith("网络错误：连接被拒绝"))
        assertTrue(
            describe(IOException("Too many follow-up requests: 21")).message.startsWith("网络错误：重定向失败"),
        )
    }

    @Test
    fun `an unknown failure still includes its class and message`() {
        val described = describe(IllegalStateException("unexpected"))
        assertTrue(described.message, described.message.startsWith("网络错误：IllegalStateException"))
        assertTrue(described.message, described.message.contains("unexpected"))
    }

    @Test
    fun `the detail keeps the cause chain`() {
        val described = describe(IOException("wrapper", SocketTimeoutException("timeout")))
        assertTrue(described.detail, described.detail.contains("cause=SocketTimeoutException"))
    }
}
