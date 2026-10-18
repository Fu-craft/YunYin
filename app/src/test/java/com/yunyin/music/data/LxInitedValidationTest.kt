package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxImportedSource
import com.yunyin.music.data.lxsource.LxInitedValidator
import com.yunyin.music.data.lxsource.LxScriptHeader
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.lxsource.findLxSourceKeyConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `inited` payload is the script's declaration of what it can do, and phase 2 asks for URLs based on it.
 *
 * The cases here follow LX's own `handleInit`: unknown keys and non-music entries are ignored, unknown actions
 * and quality tiers are filtered out, and only a structurally missing declaration (no `actions`/`qualitys`
 * array, or nothing usable at all) is an error. That is deliberately *less* strict than the prose doc, because
 * a shipped aggregator declares quality tiers beyond the four and LX accepts it.
 */
class LxInitedValidationTest {

    private val validPayload = """
        {
          "openDevTools": false,
          "sources": {
            "kw": {
              "name": "酷我音乐",
              "type": "music",
              "actions": ["musicUrl"],
              "qualitys": ["128k", "320k", "flac", "flac24bit"]
            },
            "local": {
              "name": "本地音乐",
              "type": "music",
              "actions": ["musicUrl", "lyric", "pic"],
              "qualitys": []
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a well-formed declaration is read key by key`() {
        val sources = ok(validPayload)
        assertEquals(listOf(LxSourceKey.KW, LxSourceKey.LOCAL), sources.map { it.key })
        assertEquals(listOf("musicUrl"), sources[0].actions)
        assertEquals(listOf("128k", "320k", "flac", "flac24bit"), sources[0].qualities)
        assertEquals("酷我音乐", sources[0].name)
        assertEquals(listOf("musicUrl", "lyric", "pic"), sources[1].actions)
        assertTrue(sources[1].qualities.isEmpty())
    }

    @Test
    fun `a source name is optional in the protocol`() {
        val sources = ok("""{"sources":{"wy":{"type":"music","actions":["musicUrl"],"qualitys":["128k"]}}}""")
        assertEquals(listOf(LxSourceKey.WY), sources.map { it.key })
        assertNull(sources[0].name)
    }

    @Test
    fun `payload that is not an object is refused`() {
        assertTrue(fail("[]").message.contains("JSON"))
        assertTrue(fail("not json").message.contains("JSON"))
    }

    @Test
    fun `a payload without sources is refused`() {
        assertTrue(fail("""{"openDevTools":true}""").message.contains("缺少 sources"))
    }

    @Test
    fun `a declaration that filters down to no sources is refused`() {
        assertTrue(fail("""{"sources":{}}""").message.contains("没有声明可用的音乐源"))
    }

    @Test
    fun `an unknown source key is ignored, not refused`() {
        // LX's handleInit iterates its own six keys; a `qq` entry is simply never read. Refusing it would
        // reject a script that LX itself runs.
        val sources = ok(
            """{"sources":{"qq":{"type":"music","actions":["musicUrl"],"qualitys":[]},"wy":{"type":"music","actions":["musicUrl"],"qualitys":["128k"]}}}""",
        )
        assertEquals(listOf(LxSourceKey.WY), sources.map { it.key })
    }

    @Test
    fun `a non-music entry is skipped`() {
        val sources = ok(
            """{"sources":{"wy":{"type":"video","actions":["musicUrl"],"qualitys":["128k"]},"tx":{"type":"music","actions":["musicUrl"],"qualitys":["128k"]}}}""",
        )
        assertEquals(listOf(LxSourceKey.TX), sources.map { it.key })
    }

    @Test
    fun `unknown actions are filtered, not refused`() {
        val nonLocal = ok(
            """{"sources":{"wy":{"type":"music","actions":["musicUrl","lyric"],"qualitys":["128k"]}}}""",
        )
        assertEquals(listOf("musicUrl"), nonLocal.single().actions)

        val local = ok(
            """{"sources":{"local":{"type":"music","actions":["musicUrl","radio"],"qualitys":[]}}}""",
        )
        assertEquals(listOf("musicUrl"), local.single().actions)
    }

    @Test
    fun `unknown quality tiers are filtered out, not refused`() {
        // The shipped aggregator declares hires/atmos/master alongside the standard tiers. LX filters them
        // with `supportQualitys[...].filter(q => declared.includes(q))`; rejecting the whole declaration would
        // refuse a source that works on LX.
        val sources = ok(
            """{"sources":{"wy":{"type":"music","actions":["musicUrl"],"qualitys":["128k","320k","flac","flac24bit","hires","atmos","master"]}}}""",
        )
        assertEquals(listOf("128k", "320k", "flac", "flac24bit"), sources.single().qualities)
    }

    @Test
    fun `a source declaring only unknown tiers is accepted with an empty quality list`() {
        val sources = ok(
            """{"sources":{"wy":{"type":"music","actions":["musicUrl"],"qualitys":["hires"]}}}""",
        )
        assertTrue(sources.single().qualities.isEmpty())
    }

    @Test
    fun `local qualitys are filtered to empty`() {
        val sources = ok(
            """{"sources":{"local":{"type":"music","actions":["musicUrl"],"qualitys":["128k"]}}}""",
        )
        assertTrue(sources.single().qualities.isEmpty())
    }

    @Test
    fun `a missing actions array is refused but a missing qualitys array is tolerated`() {
        assertTrue(
            fail("""{"sources":{"wy":{"type":"music","qualitys":["128k"]}}}""").message.contains("actions"),
        )
        // LX mobile accepts a source without `qualitys` (it stores it straight into qualityList); a maintained
        // multi-source script declares `local` that way. Refusing it rejected a script LX itself runs.
        val sources = ok("""{"sources":{"wy":{"type":"music","actions":["musicUrl"]}}}""")
        assertEquals(listOf(LxSourceKey.WY), sources.map { it.key })
        assertTrue(sources.single().qualities.isEmpty())
    }

    @Test
    fun `a non-string quality is skipped rather than coerced`() {
        // LX's filter compares against strings, so `320` never matches a tier; the source is still usable with
        // the tiers that did match (none here).
        val sources = ok("""{"sources":{"wy":{"type":"music","actions":["musicUrl"],"qualitys":[320]}}}""")
        assertTrue(sources.single().qualities.isEmpty())
    }

    @Test
    fun `descriptors follow LX key order, not the payload order`() {
        val sources = ok(
            """{"sources":{"local":{"type":"music","actions":["musicUrl"],"qualitys":[]},"wy":{"type":"music","actions":["musicUrl"],"qualitys":["128k"]}}}""",
        )
        assertEquals(listOf(LxSourceKey.WY, LxSourceKey.LOCAL), sources.map { it.key })
    }

    // ------------------------------------------------------------------ collision rule

    @Test
    fun `two enabled sources may not claim the same key`() {
        val existing = source(id = "a", name = "A", keys = listOf(LxSourceKey.WY, LxSourceKey.TX), enabled = true)
        val candidate = source(id = "b", name = "B", keys = listOf(LxSourceKey.MG, LxSourceKey.WY), enabled = true)
        val conflict = findLxSourceKeyConflict(listOf(existing), candidate)
        assertNotNull(conflict)
        assertEquals(LxSourceKey.WY, conflict!!.first)
        assertEquals("A", conflict.second.header.name)
    }

    @Test
    fun `sources with disjoint keys may both be enabled`() {
        val existing = source(id = "a", name = "A", keys = listOf(LxSourceKey.WY), enabled = true)
        val candidate = source(id = "b", name = "B", keys = listOf(LxSourceKey.TX), enabled = true)
        assertNull(findLxSourceKeyConflict(listOf(existing), candidate))
    }

    @Test
    fun `re-enabling the same source is not a conflict with itself`() {
        val existing = source(id = "a", name = "A", keys = listOf(LxSourceKey.WY), enabled = true)
        assertNull(findLxSourceKeyConflict(listOf(existing), existing))
    }

    @Test
    fun `the local key collides like any other`() {
        val existing = source(id = "a", name = "A", keys = listOf(LxSourceKey.LOCAL), enabled = true)
        val candidate = source(id = "b", name = "B", keys = listOf(LxSourceKey.LOCAL), enabled = true)
        assertEquals(LxSourceKey.LOCAL, findLxSourceKeyConflict(listOf(existing), candidate)?.first)
    }

    @Test
    fun `a disabled source does not conflict`() {
        val existing = source(id = "a", name = "A", keys = listOf(LxSourceKey.WY), enabled = false)
        val candidate = source(id = "b", name = "B", keys = listOf(LxSourceKey.WY), enabled = true)
        // The caller passes only the enabled set; this asserts the documented contract of that parameter.
        val enabledOnly = listOf(existing).filter { it.enabled }
        assertNull(findLxSourceKeyConflict(enabledOnly, candidate))
    }

    private fun source(
        id: String,
        name: String,
        keys: List<LxSourceKey>,
        enabled: Boolean,
    ) = LxImportedSource(
        id = id,
        header = LxScriptHeader(name = name),
        keys = keys,
        importedAt = 0L,
        enabled = enabled,
    )

    private fun ok(payload: String): List<com.yunyin.music.data.lxsource.LxSourceDescriptor> =
        when (val result = LxInitedValidator.validate(payload)) {
            is LxInitedValidator.Result.Ok -> result.sources
            is LxInitedValidator.Result.Error -> throw AssertionError("expected a declaration, got: ${result.message}")
        }

    private fun fail(payload: String): LxInitedValidator.Result.Error =
        when (val result = LxInitedValidator.validate(payload)) {
            is LxInitedValidator.Result.Ok -> throw AssertionError("expected a failure, got: ${result.sources}")
            is LxInitedValidator.Result.Error -> result
        }
}
