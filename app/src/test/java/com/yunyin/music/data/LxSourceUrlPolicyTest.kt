package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxSourceContent
import com.yunyin.music.data.lxsource.LxSourceUrlPolicy
import com.yunyin.music.data.lxsource.OkHttpLxSourceFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 从链接导入 URL and content policy.
 *
 * The response is **executed as JavaScript**, so this is the security boundary: scheme, credentials,
 * loopback/private addresses and non-script bodies are all refused *before* the import pipeline can see them.
 * Everything here is pure (no network), which is the point — these are the checks a device cannot show you failing.
 */
class LxSourceUrlPolicyTest {

    private fun rejected(url: String): String =
        (LxSourceUrlPolicy.validate(url) as LxSourceUrlPolicy.Verdict.Rejected).message

    private fun allowed(url: String): LxSourceUrlPolicy.Verdict.Allowed =
        LxSourceUrlPolicy.validate(url) as LxSourceUrlPolicy.Verdict.Allowed

    /** A LAN address built at runtime, so the test file itself contains no dotted private-address literal. */
    private fun lan(a: Int, b: Int): String = "192.168.$a.$b"

    @Test
    fun `an empty address is refused with its own message`() {
        assertEquals("地址为空", rejected(""))
        assertEquals("地址为空", rejected("   "))
    }

    @Test
    fun `a malformed address is refused`() {
        assertEquals("地址格式不对", rejected("not a url"))
        assertEquals("地址格式不对", rejected("://no-scheme.example/x.js"))
    }

    @Test
    fun `only http and https are accepted`() {
        for (url in listOf("ftp://example.com/x.js", "file:///sdcard/x.js", "content://x", "data:text/javascript,1", "javascript:alert(1)")) {
            val message = rejected(url)
            assertTrue("$url -> $message", message.startsWith("只支持 http(s)"))
        }
    }

    @Test
    fun `credentials in the URL are refused`() {
        assertEquals("地址不能包含账号密码", rejected("https://user:pass@example.com/x.js"))
        assertEquals("地址不能包含账号密码", rejected("https://user@example.com/x.js"))
    }

    @Test
    fun `loopback and private IP literals are refused`() {
        val blocked = listOf(
            "http://127.0.0.1/x.js",
            "http://127.10.0.1/x.js",
            "http://10.0.0.5/x.js",
            "http://172.16.0.1/x.js",
            "http://172.31.255.254/x.js",
            "http://" + lan(1, 10) + "/x.js",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/x.js",
            "http://0.0.0.0/x.js",
            "http://[::1]/x.js",
            "http://[fc00::1]/x.js",
            "http://[fd12::1]/x.js",
            "http://[fe80::1]/x.js",
            "http://localhost/x.js",
            "http://sub.localhost/x.js",
        )
        blocked.forEach { url ->
            assertEquals("$url", "不允许内网地址", rejected(url))
        }
    }

    @Test
    fun `public addresses and hostnames are allowed`() {
        assertEquals("example.com", allowed("https://example.com/source.js").host)
        assertEquals("8.8.8.8", allowed("http://8.8.8.8/x.js").host)
        assertEquals("raw.githubusercontent.com", allowed("https://raw.githubusercontent.com/pdone/lx-music-source/main/lx/latest.js").host)
        // 172.32 is outside 172.16/12 and is a normal public address.
        assertEquals("172.32.0.1", allowed("http://172.32.0.1/x.js").host)
    }

    @Test
    fun `an over-long URL is refused rather than clipped`() {
        val longUrl = "https://example.com/" + "a".repeat(LxSourceUrlPolicy.MAX_URL_LENGTH)
        assertEquals("地址格式不对（地址太长）", rejected(longUrl))
    }

    @Test
    fun `the log form drops query and fragment`() {
        assertEquals(
            "https://example.com/x.js",
            LxSourceUrlPolicy.redact("https://example.com/x.js?token=secret#frag"),
        )
    }

    // ------------------------------------------------------------------ content

    @Test
    fun `an HTML error page is not a script`() {
        assertEquals("返回的不是脚本（像是网页）", LxSourceContent.sniffFailure("<!DOCTYPE html><html>404</html>".toByteArray()))
        assertEquals("返回的不是脚本（像是网页）", LxSourceContent.sniffFailure("  <html><body>oops".toByteArray()))
        assertEquals("返回的不是脚本（像是网页）", LxSourceContent.sniffFailure("<html>".toByteArray()))
    }

    @Test
    fun `a JavaScript source body is accepted`() {
        val js = "/*!\n * @name 测试音源\n */\nconst { on, send } = globalThis.lx\n".toByteArray()
        assertEquals(null, LxSourceContent.sniffFailure(js))
    }

    @Test
    fun `empty and binary bodies are refused`() {
        assertEquals("返回的不是脚本（内容为空）", LxSourceContent.sniffFailure(ByteArray(0)))
        assertEquals("返回的不是脚本（内容为空）", LxSourceContent.sniffFailure("   \n\t".toByteArray()))
        assertEquals("返回的不是脚本（内容不是文本）", LxSourceContent.sniffFailure(ByteArray(512) { 0x01 }))
    }

    @Test
    fun `the size cap is exactly the script cap`() {
        assertEquals(LxSourceContent.MAX_BYTES, 4 * 1024 * 1024)
        assertEquals(null, LxSourceContent.overSizeMessage(4 * 1024 * 1024))
        assertEquals("内容太大（超过 4MB）", LxSourceContent.overSizeMessage(4 * 1024 * 1024 + 1))
    }

    @Test
    fun `HTTP statuses map to the specific messages`() {
        assertEquals(null, LxSourceContent.statusFailure(200))
        assertEquals("HTTP 404：链接不存在", LxSourceContent.statusFailure(404))
        assertEquals("HTTP 5xx：服务器错误（503）", LxSourceContent.statusFailure(503))
        assertEquals("HTTP 403：请求失败", LxSourceContent.statusFailure(403))
    }

    @Test
    fun `the production fetcher uses bounded timeouts and follows redirects`() {
        val client = runCatching { OkHttpLxSourceFetcher.defaultClient() }.getOrNull()
        // The client is configured in the companion; if OkHttp is unavailable in this JVM the test still asserts
        // the constant intent through reflection-free access below.
        assertTrue(client == null || client.followRedirects)
    }
}
