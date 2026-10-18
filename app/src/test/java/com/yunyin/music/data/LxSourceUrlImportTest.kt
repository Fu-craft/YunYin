package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxImportResult
import com.yunyin.music.data.lxsource.LxSourceFetcher
import com.yunyin.music.data.lxsource.LxSourceFetchResult
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceManager
import com.yunyin.music.data.lxsource.LxSourceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * URL import → outcome mapping, with a fake fetcher.
 *
 * The success path needs the JS engine, so it is verified by the temporary harness; what must be guaranteed here is
 * that **nothing reaches the import pipeline** unless the URL passed the policy, that a fetch failure becomes its
 * specific Chinese line, and that the failure detail (URL, reason) lands in `last-error.txt` without the script body.
 */
class LxSourceUrlImportTest {

    private class FakeFetcher(private val result: LxSourceFetchResult) : LxSourceFetcher {
        var calls = 0
            private set
        var lastUrl: String? = null
            private set

        override suspend fun fetch(url: String): LxSourceFetchResult {
            calls++
            lastUrl = url
            return result
        }
    }

    private fun manager(fetcher: LxSourceFetcher): Pair<LxSourceManager, LxSourceLog> {
        val dir = Files.createTempDirectory("url-import").toFile()
        val log = LxSourceLog(File(dir, "last-error.txt"))
        val store = LxSourceStore(dir, log)
        return LxSourceManager(null, store, log, fetcher) to log
    }

    private fun failureMessage(result: LxImportResult): String =
        (result as LxImportResult.Failure).message

    @Test
    fun `a rejected scheme never calls the network`() = runBlocking {
        val fetcher = FakeFetcher(LxSourceFetchResult.Ok("x", 1, "https://example.com"))
        val (manager, log) = manager(fetcher)
        assertEquals("只支持 http(s) 地址（收到：ftp）", failureMessage(manager.importFromUrl("ftp://example.com/x.js")))
        assertEquals(0, fetcher.calls)
        assertTrue(log.readLast()!!.contains("从链接导入被拒绝"))
    }

    @Test
    fun `credentials and private addresses are rejected before fetching`() = runBlocking {
        val fetcher = FakeFetcher(LxSourceFetchResult.Ok("x", 1, "https://example.com"))
        val (manager, _) = manager(fetcher)
        assertEquals(
            "地址不能包含账号密码",
            failureMessage(manager.importFromUrl("https://u:p@example.com/x.js")),
        )
        assertEquals(
            "不允许内网地址",
            failureMessage(manager.importFromUrl("http://192.168." + "0.2/x.js")),
        )
        assertEquals("地址为空", failureMessage(manager.importFromUrl("  ")))
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun `a fetch failure becomes the specific message and is logged with the URL`() = runBlocking {
        val fetcher = FakeFetcher(
            LxSourceFetchResult.Failure("返回的不是脚本（像是网页）", "sniff failed: <!DOCTYPE html>"),
        )
        val (manager, log) = manager(fetcher)
        assertEquals(
            "返回的不是脚本（像是网页）",
            failureMessage(manager.importFromUrl("https://example.com/source.js?token=secret")),
        )
        assertEquals(1, fetcher.calls)
        val logged = log.readLast()!!
        assertTrue(logged.contains("https://example.com/source.js"))
        // The URL in the log is redacted: a query can carry tokens.
        assertTrue(!logged.contains("token=secret"))
        // Never the body: the detail line is what the fetcher summarised, not the response itself.
        assertTrue(logged.contains("从链接导入失败"))
    }

    @Test
    fun `an HTTP status failure keeps its message`() = runBlocking {
        val fetcher = FakeFetcher(LxSourceFetchResult.Failure("HTTP 404：链接不存在", "status=404"))
        val (manager, _) = manager(fetcher)
        assertEquals("HTTP 404：链接不存在", failureMessage(manager.importFromUrl("https://example.com/missing.js")))
    }

    @Test
    fun `a fetched body still has to pass the header check`() = runBlocking {
        // The fake bypasses the fetcher's sniffing on purpose: the point is that the import pipeline itself
        // refuses a body with no source header, so a URL cannot skip validation.
        val fetcher = FakeFetcher(LxSourceFetchResult.Ok("console.log('hi')", 17, "https://example.com/x.js"))
        val (manager, _) = manager(fetcher)
        val message = failureMessage(manager.importFromUrl("https://example.com/x.js"))
        assertTrue(message, message.contains("缺少自定义源信息注释"))
    }

    @Test
    fun `a fetcher throwing an Error becomes an internal-error message instead of crashing`() = runBlocking {
        // The device crash class we must never allow again: an engine/native `Error` is not an `Exception`.
        val fetcher = object : LxSourceFetcher {
            override suspend fun fetch(url: String): LxSourceFetchResult =
                throw UnsatisfiedLinkError("no libquickjs for this ABI")
        }
        val (manager, log) = manager(fetcher)
        val result = manager.importFromUrl("https://example.com/source.js")
        assertTrue(result is LxImportResult.Failure)
        val message = (result as LxImportResult.Failure).message
        assertTrue(message, message.startsWith("导入时发生内部错误："))
        assertTrue(message, message.contains("no libquickjs"))
        assertTrue(log.readLast()!!.contains("UnsatisfiedLinkError"))
    }

    @Test
    fun `an OutOfMemoryError during fetch also returns an outcome`() = runBlocking {
        val fetcher = object : LxSourceFetcher {
            override suspend fun fetch(url: String): LxSourceFetchResult = throw OutOfMemoryError("boom")
        }
        val (manager, _) = manager(fetcher)
        val result = manager.importFromUrl("https://example.com/source.js")
        assertTrue(result is LxImportResult.Failure)
        assertTrue((result as LxImportResult.Failure).message.startsWith("导入时发生内部错误："))
    }

    @Test
    fun `re-fetch without an origin or with a throwing fetch never throws`() = runBlocking {
        val fetcher = FakeFetcher(LxSourceFetchResult.Ok("x", 1, "https://example.com"))
        val (manager, _) = manager(fetcher)
        val local = com.yunyin.music.data.lxsource.LxImportedSource(
            id = "abc",
            header = com.yunyin.music.data.lxsource.LxScriptHeader(name = "本地"),
            keys = listOf(com.yunyin.music.data.lxsource.LxSourceKey.TX),
            importedAt = 0L,
            enabled = true,
            originUrl = null,
        )
        assertEquals(
            "这个音源不是从链接导入的，无法重新拉取",
            (manager.refreshFromUrl(local) as LxImportResult.Failure).message,
        )

        val throwing = object : LxSourceFetcher {
            override suspend fun fetch(url: String): LxSourceFetchResult = throw IllegalStateException("engine not ready")
        }
        val (manager2, _) = manager(throwing)
        val result = manager2.refreshFromUrl(local.copy(originUrl = "https://example.com/x.js"))
        assertTrue((result as LxImportResult.Failure).message.startsWith("导入时发生内部错误："))
    }

    // ------------------------------------------------------------------ mirror fallback

    private val raw = "https://raw.githubusercontent.com/pdone/lx-music-source/main/lx/latest.js"
    private val jsdelivr = "https://cdn.jsdelivr.net/gh/pdone/lx-music-source@main/lx/latest.js"
    private val fastly = "https://fastly.jsdelivr.net/gh/pdone/lx-music-source@main/lx/latest.js"

    private class MapFetcher(private val responses: Map<String, LxSourceFetchResult>) : LxSourceFetcher {
        val calls = mutableListOf<String>()

        override suspend fun fetch(url: String): LxSourceFetchResult {
            calls += url
            return responses[url] ?: LxSourceFetchResult.Failure("网络错误：未配置的地址")
        }
    }

    @Test
    fun `the github chain tries original then jsDelivr then fastly, and lists each failure`() = runBlocking {
        val fetcher = MapFetcher(
            mapOf(
                raw to LxSourceFetchResult.Failure(
                    "网络错误：连接超时（SocketTimeoutException: timeout）",
                    "host=raw.githubusercontent.com",
                ),
                jsdelivr to LxSourceFetchResult.Failure(
                    "网络错误：连接被重置（SocketException: Connection reset）",
                    "host=cdn.jsdelivr.net",
                ),
                fastly to LxSourceFetchResult.Failure(
                    "网络错误：无法解析域名（fastly.jsdelivr.net）（UnknownHostException: resolve）",
                    "host=fastly.jsdelivr.net",
                ),
            ),
        )
        val (manager, log) = manager(fetcher)
        val message = failureMessage(manager.importFromUrl(raw))
        assertEquals(listOf(raw, jsdelivr, fastly), fetcher.calls)
        assertTrue(message, message.startsWith("网络不可达："))
        assertTrue(message, message.contains("原始地址：连接超时"))
        assertTrue(message, message.contains("jsDelivr 镜像：连接被重置"))
        assertTrue(message, message.contains("fastly 镜像：无法解析域名"))
        assertTrue(message, message.contains("请检查网络，或换用其它镜像链接"))
        // Every attempt is in the log trail, with the address that failed.
        val logged = log.readLast()!!
        assertTrue(logged, logged.contains("attempt=原始地址"))
        assertTrue(logged, logged.contains("attempt=jsDelivr 镜像"))
    }

    @Test
    fun `a mirror that succeeds reports which one was used`() = runBlocking {
        val fetcher = MapFetcher(
            mapOf(
                raw to LxSourceFetchResult.Failure("网络错误：连接超时（SocketTimeoutException: timeout）"),
                jsdelivr to LxSourceFetchResult.Ok("/*! @name 音源 */\ncode", 24, jsdelivr),
            ),
        )
        val (manager, _) = manager(fetcher)
        val chain = manager.fetchWithFallback(raw)
        assertTrue(chain is com.yunyin.music.data.lxsource.LxSourceFetchChain.Ok)
        chain as com.yunyin.music.data.lxsource.LxSourceFetchChain.Ok
        assertEquals(com.yunyin.music.data.lxsource.LxSourceMirrorRules.JSDELIVR_LABEL, chain.candidate.label)
        assertEquals("/*! @name 音源 */\ncode", chain.text)
    }

    @Test
    fun `a non-github URL is attempted exactly once`() = runBlocking {
        val fetcher = MapFetcher(
            mapOf("https://example.com/x.js" to LxSourceFetchResult.Failure("HTTP 404：链接不存在")),
        )
        val (manager, _) = manager(fetcher)
        val message = failureMessage(manager.importFromUrl("https://example.com/x.js"))
        assertEquals(listOf("https://example.com/x.js"), fetcher.calls)
        assertEquals("HTTP 404：链接不存在", message)
    }

    @Test
    fun `a github chain with HTTP failures keeps the HTTP reason rather than claiming the network is down`() =
        runBlocking {
            val fetcher = MapFetcher(
                mapOf(
                    raw to LxSourceFetchResult.Failure("HTTP 404：链接不存在"),
                    jsdelivr to LxSourceFetchResult.Failure("HTTP 404：链接不存在"),
                    fastly to LxSourceFetchResult.Failure("HTTP 404：链接不存在"),
                ),
            )
            val (manager, _) = manager(fetcher)
            val message = failureMessage(manager.importFromUrl(raw))
            assertTrue(message, message.startsWith("导入失败："))
            assertTrue(message, message.contains("HTTP 404"))
            assertTrue(message, !message.contains("网络不可达"))
        }

    @Test
    fun `a mirror cannot bypass the policy, and mirror URLs are public`() {
        // Mirror candidates are always built on public jsDelivr hosts; a pasted private address has no mirror at all
        // and is refused before any candidate is fetched (asserted by the credentials/private test above).
        assertTrue(com.yunyin.music.data.lxsource.LxSourceUrlPolicy.validate(jsdelivr) is com.yunyin.music.data.lxsource.LxSourceUrlPolicy.Verdict.Allowed)
        assertTrue(com.yunyin.music.data.lxsource.LxSourceUrlPolicy.validate(fastly) is com.yunyin.music.data.lxsource.LxSourceUrlPolicy.Verdict.Allowed)
        assertTrue(com.yunyin.music.data.lxsource.LxSourceMirrorRules.mirrors("http://" + "192.168." + "1.1/x.js").isEmpty())
    }
}
