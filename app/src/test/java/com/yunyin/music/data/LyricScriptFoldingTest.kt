package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding a translation that the source baked into the lyric track.
 *
 * The body of the first test is the **verbatim opening of the reported track's `lrc` field** (id
 * 2060689311, `Purple Whisper`), fetched from the service. Its `tlyric`, `yrc` and `ytlrc` are all
 * empty while `lrc` interleaves the translation, which is why the translation was rendered and
 * animated as a lyric.
 *
 * The remaining tests exist because this is a heuristic: a bilingual song is *also* mixed-script, and
 * folding one would delete real lyrics.
 */
class LyricScriptFoldingTest {

    /** Verbatim from the service, alternating original and translation. */
    private val realInterleaved = """
        [00:08.160]I have some purple in my mind, bae.
        [00:09.840]我心里一直有一抹紫，宝贝。
        [00:10.920]It's not a really good time.
        [00:12.330]今皆吾犹灰日也
        [00:12.780]Love in the streets you know she raised me.
        [00:14.880]街头的爱，你知道，是它把我养大
        [00:15.270]Gotta go out wild, wild, wild.
        [00:16.200]我得冲出去，肆意地、疯狂地闯一场
        [00:16.920]I'm lover boy that's not my type, shit,
        [00:18.900]我是个情场浪子——可那根本不是我的路，*。
    """.trimIndent()

    private fun synced(vararg pairs: Pair<String, Int>) = SyncedLyrics(
        lines = pairs.map { (text, start) -> SyncedLine(text, null, start, start + 1_500) },
    )

    /** Parses the LRC body the way the app does, so the test starts from the same input. */
    private fun parse(body: String): SyncedLyrics {
        val tag = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
        val lines = body.split("\n").mapNotNull { raw ->
            val m = tag.find(raw.trim()) ?: return@mapNotNull null
            val frac = m.groupValues[3]
            val millis = when (frac.length) {
                0 -> 0
                1 -> frac.toInt() * 100
                2 -> frac.toInt() * 10
                else -> frac.take(3).toInt()
            }
            val start = m.groupValues[1].toInt() * 60_000 + m.groupValues[2].toInt() * 1_000 + millis
            SyncedLine(m.groupValues[4].trim(), null, start, start + 1_500)
        }
        return SyncedLyrics(lines = lines)
    }

    @Test
    fun `the reported track's translation is folded into its lyric lines`() {
        val parsed = parse(realInterleaved)
        assertEquals("the payload interleaves both languages", 10, parsed.lines.size)

        val folded = LyricScriptFolding.foldInterleavedTranslation(parsed)

        assertEquals("every translation line should be folded away", 5, folded.lines.size)
        assertEquals("I have some purple in my mind, bae.", (folded.lines[0] as SyncedLine).content)
        assertEquals("我心里一直有一抹紫，宝贝。", (folded.lines[0] as SyncedLine).translation)
        assertEquals("It's not a really good time.", (folded.lines[1] as SyncedLine).content)
        assertEquals("今皆吾犹灰日也", (folded.lines[1] as SyncedLine).translation)

        // The lyric lines are the survivors and the translations are gone, so nothing is animated as
        // a lyric that is really a translation.
        folded.lines.forEach { line ->
            val text = (line as SyncedLine).content
            assertTrue("'$text' should be a lyric, not a translation", !isMostlyChinese(text))
        }
    }

    /**
     * The reported track's **entire** lyric field, verbatim as the service returned it.
     *
     * Used rather than the opening alone because the thresholds were tuned by measuring the whole
     * document, and because the tail contains the case that is hardest to get right — see
     * [the one-to-many translation case]. A document that folds only when it is short would pass a
     * test built from the first few lines.
     */
    private val realFullPayload = """
        [00:08.160]I have some purple in my mind, bae.
        [00:09.840]我心里一直有一抹紫，宝贝。
        [00:10.920]It's not a really good time.
        [00:12.330]今皆吾犹灰日也
        [00:12.780]Love in the streets you know she raised me.
        [00:14.880]街头的爱，你知道，是它把我养大
        [00:15.270]Gotta go out wild, wild, wild.
        [00:16.200]我得冲出去，肆意地、疯狂地闯一场
        [00:16.920]I'm lover boy that's not my type, shit,
        [00:18.900]我是个情场浪子——可那根本不是我的路，*。
        [00:19.560]You can't take a piece of mine.
        [00:21.000]你别想从我这里夺走一分一毫。
        [00:21.300]Pretty little nasty purple emoji when she all on my ride ride ride.
        [00:24.990]她贴在我的车上，像一枚漂亮又带着坏劲的混乱表情，摇曳着、摇曳着、摇曳着。
        [00:25.650]Pleasure last in a min and it goes down.
        [00:27.810]乐转瞬即逝矣
        [00:28.350]If you all right?I'mbreathin' now.
        [00:30.090]彼问我子尚好否，我云尚能呼吸
        [00:30.960]Hard to sayit's right or wrong cuzain't nobody lives in a fairy tale.
        [00:34.170]很难说这一切究竟对错，毕竟没有人真的活在童话里。
        [00:34.950]I know that I got a problem, my friend, could you hold me down.
        [00:38.610]我知道自己身上有问题，朋友，你能不能拉住我。
        [00:39.840]Safe to sayit's mess right now, how I, put you down I could drag you out.
        [00:43.110]可以肯定的是，现在已经乱成了一团；若是我让你跌落，我也会把你从泥里拖出来。
        [00:44.010]I know you got emotions could you let me in just once.
        [00:47.100]我知道你心里藏着情绪，能不能只让我走进去一次。
        [00:48.150]Alwayssomethin' right hereit's okay you wanna run.
        [00:51.180]这里总有些什么在纠缠——没关系，如果你想逃，就逃吧。
        [00:52.290]You gotta hide somewhere, somewhere you know theycan't find you.
        [00:55.590]你要找个地方躲起来，一个他们永远找不到你的地方。
        [00:56.760]End of the day I'm your Miles cross the universe find you.
        [00:59.850]可到了最后，我会是你的 Miles——哪怕穿越整个宇宙，我也一定找到你
        [01:01.020]I apologize for my bad habits, this my life I'm addicted to it.
        [01:04.530]原谅我的坏习惯吧，这就是我的生活，而我早已沉溺其中。
        [01:05.550]Ain't nobody tell melivin' right,I'mlivin'rightdon't blow my high.
        [01:09.330]没人有资格教我该怎样活，我自有我的活法——别来扰乱我的兴致。
        [01:10.260]I'm a pretty little monster they tryna bring me down theycan't see me fly.
        [01:13.890]我是只漂亮的小怪物，他们想把我拽下去——可他们看不见我正在飞。
        [01:14.760]I'ma keep it real to the tomb when I do my crime I do my time.
        [01:17.940]我会一直活得真实，直到坟墓；我犯下的错，我自己承担代价。
        [01:19.140]I know you wanna ride with me.
        [01:20.670]Hop in the car spend some time with me.
        [01:22.590]上车吧，陪我走一段。
        [01:22.950]Hang up the phone don't you worry about it.
        [01:24.720]把电话挂了，别再为那些事烦心。
        [01:25.110]It's a long day we got all night free.
        [01:28.050]今天太漫长了——好在，我们还有整夜的时间。
        [01:29.460]Tell me what you want what you wanna be.
        [01:30.960]告诉我，你想要什么，你想成为怎样的人。
        [01:31.560]Tell me what you want what you wanna be.
        [01:33.120]告诉我，你想要什么，你想成为怎样的人。
        [01:33.690]Tell me what you want what you wanna be.
        [01:36.030]告诉我，你想要什么，你想成为怎样的人。
    """.trimIndent()

    @Test
    fun `the entire real payload folds`() {
        val parsed = parse(realFullPayload)
        assertEquals("the payload as returned", 53, parsed.lines.size)

        val folded = LyricScriptFolding.foldInterleavedTranslation(parsed)

        // 20 alternating pairs, then the one-to-many case (two English lines then one Chinese), then
        // 5 more pairs: 20 + 2 + 5 = 27.
        assertEquals("folded line count", 27, folded.lines.size)

        // The 26 lines whose translation is one-to-one carry it; only the earlier of the two English
        // lines in the one-to-many case is left without one.
        val lyricLines = folded.lines.map { it as SyncedLine }
        val withoutTranslation = lyricLines.filter { it.translation == null }
        assertEquals(
            "only the line preceding the one-to-many translation should lack one",
            1,
            withoutTranslation.size,
        )
        assertEquals(
            "I know you wanna ride with me.",
            withoutTranslation.single().content,
        )
    }

    /**
     * A translation containing a Latin word must still fold.
     *
     * Recorded because an earlier version of the rule required a line to be *purely* one script, which
     * classified this line as mixed and left it unfolded — the reported bug, surviving on one of the
     * reported song's own lines:
     *
     * ```
     * [00:59.850]可到了最后，我会是你的 Miles——哪怕穿越整个宇宙，我也一定找到你
     * ```
     *
     * The rule now takes the *dominant* script, so a name inside a Chinese line does not disqualify it.
     */
    @Test
    fun `a translation containing a Latin name still folds`() {
        val folded = LyricScriptFolding.foldInterleavedTranslation(parse(realFullPayload))
        val holder = folded.lines
            .map { it as SyncedLine }
            .firstOrNull { it.content == "End of the day I'm your Miles cross the universe find you." }

        assertEquals(
            "可到了最后，我会是你的 Miles——哪怕穿越整个宇宙，我也一定找到你",
            holder?.translation,
        )
    }

    /**
     * The one-to-many case, recorded as a known limitation rather than a bug.
     *
     * In the real payload one Chinese line translates **two** English lines:
     *
     * ```
     * [01:19.140]I know you wanna ride with me.
     * [01:20.670]Hop in the car spend some time with me.
     * [01:22.590]上车吧，陪我走一段。
     * ```
     *
     * The fold attaches a translation to the preceding lyric line, so it lands on the *second* English
     * line and only appears from there. The alternative — leaving it unfolded — is the bug this
     * replaces, so attaching to the nearest preceding line is the better of the two. This test pins the
     * actual behaviour so a future change cannot silently move it, and so the limitation is visible
     * rather than surprising.
     */
    @Test
    fun `the one-to-many translation lands on the nearest preceding line`() {
        val folded = LyricScriptFolding.foldInterleavedTranslation(parse(realFullPayload))
        val lines = folded.lines.map { it as SyncedLine }

        assertEquals(
            "the translation attaches to the line it follows, not the one before that",
            "上车吧，陪我走一段。",
            lines.first { it.content == "Hop in the car spend some time with me." }.translation,
        )
        assertNull(
            "the earlier line of the pair is left without one",
            lines.first { it.content == "I know you wanna ride with me." }.translation,
        )
    }

    @Test
    fun `a bilingual song with sections is not folded`() {
        // The risk this guards: verse in one language, hook in another is also "mixed script", but
        // folding it would delete real lyrics.
        val lyrics = synced(
            *((1..6).map { "english verse line $it" to it * 1_000 }.toTypedArray()),
            *((1..3).map { "中文副歌第${it}句" to (7_000 + it * 1_000) }.toTypedArray()),
            *((1..6).map { "english verse line $it again" to (11_000 + it * 1_000) }.toTypedArray()),
            *((1..3).map { "中文副歌第${it}句" to (18_000 + it * 1_000) }.toTypedArray()),
        )
        assertTrue(
            "a bilingual song must be left alone",
            LyricScriptFolding.foldInterleavedTranslation(lyrics) === lyrics,
        )
    }

    @Test
    fun `a monolingual song is not folded`() {
        val english = synced(*((1..20).map { "english line $it" to it * 1_000 }.toTypedArray()))
        assertTrue(LyricScriptFolding.foldInterleavedTranslation(english) === english)

        val chinese = synced(*((1..20).map { "中文第${it}句" to it * 1_000 }.toTypedArray()))
        assertTrue(LyricScriptFolding.foldInterleavedTranslation(chinese) === chinese)
    }

    @Test
    fun `a short document is not judged`() {
        // Two alternating lines give a ratio of 1.0 on a single pair, which decides nothing.
        val tiny = synced("Hello" to 1_000, "你好" to 2_000)
        assertTrue(LyricScriptFolding.foldInterleavedTranslation(tiny) === tiny)
    }

    @Test
    fun `lines with both scripts or neither take no part`() {
        // A credit line carrying a Chinese role label and a Latin name is neither language, so it must
        // not be read as a language switch; nor may punctuation-only lines.
        val lyrics = synced(
            "作曲: Someone" to 1_000,
            "· · ·" to 2_000,
            "english one" to 3_000,
            "中文第一句" to 4_000,
            "english two" to 5_000,
            "中文第二句" to 6_000,
            "english three" to 7_000,
            "中文第三句" to 8_000,
            "english four" to 9_000,
            "中文第四句" to 10_000,
        )
        val folded = LyricScriptFolding.foldInterleavedTranslation(lyrics)
        // The credit and the punctuation survive; the four translations are folded away.
        assertEquals(6, folded.lines.size)
        assertEquals("作曲: Someone", (folded.lines[0] as SyncedLine).content)
        assertEquals("· · ·", (folded.lines[1] as SyncedLine).content)
    }

    @Test
    fun `a line that is already translated is not overwritten`() {
        // Only an empty translation is filled; an existing one is appended to, never replaced.
        val lyrics = SyncedLyrics(
            lines = listOf(
                SyncedLine("english one", "existing", 1_000, 2_000),
                SyncedLine("中文第一句", null, 2_000, 3_000),
                SyncedLine("english two", null, 3_000, 4_000),
                SyncedLine("中文第二句", null, 4_000, 5_000),
                SyncedLine("english three", null, 5_000, 6_000),
                SyncedLine("中文第三句", null, 6_000, 7_000),
                SyncedLine("english four", null, 7_000, 8_000),
                SyncedLine("中文第四句", null, 8_000, 9_000),
            ),
        )
        val folded = LyricScriptFolding.foldInterleavedTranslation(lyrics)
        assertEquals("existing\n中文第一句", (folded.lines[0] as SyncedLine).translation)
    }

    @Test
    fun `an untouched document comes back as the same instance`() {
        // Identity matters: a new instance would re-measure every line in the renderer for nothing.
        val untouched = synced(*((1..20).map { "english line $it" to it * 1_000 }.toTypedArray()))
        assertNull(
            "nothing should be folded, so no copy should be made",
            LyricScriptFolding.foldInterleavedTranslation(untouched)
                .takeIf { it !== untouched },
        )
    }

    private fun isMostlyChinese(text: String): Boolean {
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return cjk > 0 && latin == 0
    }
}
