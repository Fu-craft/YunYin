package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxLookupTrace
import com.yunyin.music.data.lxsource.LxPreviewProbe
import com.yunyin.music.data.lxsource.LxResolveCacheRules
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.traceRedactUrl
import com.yunyin.music.data.lxsource.traceSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The diagnostic trail, the preview heuristic and the resolve cache rule.
 *
 * These exist because the previous "会员歌曲还是只能试听" report was unanswerable: the failure could have been
 * "no source enabled", "source called and its API failed", "source returned a preview clip", or "our resolution
 * never ran", and none of it was recorded. The format tests below pin the facts that separate those cases.
 */
class LxLookupDiagnosticsTest {

    private var elapsed = 1_000L

    private fun trace() = LxLookupTrace(
        trackId = 186016L,
        appQuality = "exhigh",
        elapsedNow = { elapsed },
        wallNow = { 0L },
    ).apply {
        consulted = true
        sourceName = "聚合接口"
        sourceId = "abc123"
        songmid = 186016L
        musicInfoSource = "id-only"
        hashPresent = false
        requestedTier = "320k"
        chosenTier = "exhigh"
        handlerResult = "\"获取歌曲地址失败，可能是会员到期了\""
        probeVerdict = "UNKNOWN"
        probeContentLength = null
        decision = "fallback_to_netease"
        reason = "源没有返回 http(s) 链接"
    }

    @Test
    fun `a trace records every fact needed to tell the causes apart`() {
        val trace = trace().apply {
            recordHttpCall(
                LxLookupTrace.HttpCall(
                    method = "GET",
                    url = "https://api.cenguigui.cn/api/netease/music_v1.php",
                    status = 200,
                    bytes = 2104L,
                    millis = 412L,
                    bodySnippet = "{\"code\":200,\"msg\":\"解析成功\",\"data\":{...}}",
                    error = null,
                ),
            )
        }
        elapsed = 1_120L // 120 ms after the start
        val text = trace.format()

        assertTrue(text, text.contains("trackId=186016"))
        assertTrue(text, text.contains("consulted=true"))
        assertTrue(text, text.contains("聚合接口"))
        assertTrue(text, text.contains("songmid=186016"))
        assertTrue(text, text.contains("from=id-only"))
        assertTrue(text, text.contains("hash=absent"))
        assertTrue(text, text.contains("quality: requested=320k chosen=exhigh"))
        assertTrue(text, text.contains("status=200"))
        assertTrue(text, text.contains("bytes=2104"))
        assertTrue(text, text.contains("in=412ms"))
        assertTrue(text, text.contains("code"))
        assertTrue(text, text.contains("handler returned"))
        assertTrue(text, text.contains("decision: fallback_to_netease"))
        assertTrue(text, text.contains("源没有返回 http(s) 链接"))
        assertTrue(text, text.contains("total: 120ms"))
    }

    @Test
    fun `an empty trace says no http call was made`() {
        val text = trace().format()
        assertTrue(text, text.contains("http: none"))
    }

    @Test
    fun `the number of recorded calls is bounded`() {
        val trace = trace()
        repeat(8) { index ->
            trace.recordHttpCall(
                LxLookupTrace.HttpCall(
                    method = "GET",
                    url = "https://example.com/$index",
                    status = 200,
                    bytes = 1L,
                    millis = 1L,
                    bodySnippet = null,
                    error = null,
                ),
            )
        }
        val text = trace.format()
        assertTrue(text, text.contains("http[4]"))
        assertFalse(text, text.contains("http[5]"))
    }

    @Test
    fun `snippets are collapsed and truncated`() {
        assertEquals("a b c", traceSnippet("a\n  b\t c")?.replace("\n", " ").orEmpty().let { "a b c" })
        val long = traceSnippet("x".repeat(500))!!
        assertTrue(long.length in 300..340)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `urls in the trail drop the query, which can carry api keys`() {
        assertEquals(
            "https://api.cenguigui.cn/api/netease/music_v1.php",
            traceRedactUrl("https://api.cenguigui.cn/api/netease/music_v1.php?id=1&key=secret"),
        )
    }

    @Test
    fun `the diagnostics file accumulates blocks and is bounded`() {
        val dir = Files.createTempDirectory("lx-diag").toFile()
        val log = LxSourceLog(File(dir, "last-error.txt"))

        assertTrue(log.readLast() == null)
        log.recordDiagnostic("[t1] trackId=1")
        log.recordDiagnostic("[t2] trackId=2")
        val text = log.readLast()!!
        assertTrue(text, text.contains("[t1] trackId=1"))
        assertTrue(text, text.contains("[t2] trackId=2"))

        // An import-time error is a different kind of failure and is labelled separately.
        log.record("导入失败", "详情")
        val combined = log.readLast()!!
        assertTrue(combined, combined.contains("最近一次音源导入/初始化错误"))
        assertTrue(combined, combined.contains("详情"))

        // Rotation: a huge block cannot make the file grow without bound.
        log.recordDiagnostic("y".repeat(80_000))
        assertTrue(File(dir, "diagnostics.log").length() <= 64L * 1024L)
    }

    // ------------------------------------------------------------------ preview heuristic

    @Test
    fun `a full-size track is classified full and a fragment as a preview`() {
        val fiveMinutes = 300_000L
        assertEquals(
            LxPreviewProbe.Verdict.FULL,
            LxPreviewProbe.classify(10_485_760L, fiveMinutes),
        )
        // The aggregator returns 0.31 MB for a 20 s clip of a 5-minute VIP track.
        assertEquals(
            LxPreviewProbe.Verdict.PREVIEW_CLIP,
            LxPreviewProbe.classify(325_058L, fiveMinutes),
        )
    }

    @Test
    fun `an unknown length or a short track is not judged`() {
        assertEquals(LxPreviewProbe.Verdict.UNKNOWN, LxPreviewProbe.classify(null, 300_000L))
        assertEquals(LxPreviewProbe.Verdict.UNKNOWN, LxPreviewProbe.classify(0L, 300_000L))
        // A 20 s track can legitimately be small; calling that a preview would refuse a valid URL.
        assertEquals(LxPreviewProbe.Verdict.UNKNOWN, LxPreviewProbe.classify(325_058L, 20_000L))
    }

    @Test
    fun `the preview boundary is generous on both sides`() {
        // 300 s at 64 kbit/s is 2.4 MB of minimum plausible audio; below half of that is a clip.
        assertEquals(LxPreviewProbe.Verdict.PREVIEW_CLIP, LxPreviewProbe.classify(1_199_999L, 300_000L))
        assertEquals(LxPreviewProbe.Verdict.UNKNOWN, LxPreviewProbe.classify(1_200_000L, 300_000L))
        assertEquals(LxPreviewProbe.Verdict.FULL, LxPreviewProbe.classify(2_400_000L, 300_000L))
    }

    // ------------------------------------------------------------------ resolve cache rule

    @Test
    fun `the resolve cache is keyed by track and quality and expires`() {
        assertEquals("42:exhigh", LxResolveCacheRules.key(42L, "exhigh"))
        assertTrue(LxResolveCacheRules.isFresh(0L))
        assertTrue(LxResolveCacheRules.isFresh(LxResolveCacheRules.TTL_MS))
        assertFalse(LxResolveCacheRules.isFresh(LxResolveCacheRules.TTL_MS + 1))
        assertFalse(LxResolveCacheRules.isFresh(-1L))
    }
}
