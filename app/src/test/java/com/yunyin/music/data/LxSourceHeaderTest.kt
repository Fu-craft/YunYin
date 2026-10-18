package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxScriptHeaderParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header is the one part of a custom source that can be validated without running JavaScript, and it is
 * also the first thing a user sees when an import fails. So the tests here are about the *specific refusal*:
 * every malformed shape gets a message that names what is wrong, rather than a generic "import failed".
 */
class LxSourceHeaderTest {

    private val valid = """
        /**
         * @name 测试音乐源
         * @description 我只是一个测试音乐源哦
         * @version 1.0.0
         * @author xxx
         * @homepage https://example.com/source
         */
        const { EVENT_NAMES, request, on, send } = globalThis.lx
    """.trimIndent()

    @Test
    fun `a jsdoc-shaped header is parsed field by field`() {
        val header = ok(valid)
        assertEquals("测试音乐源", header.name)
        assertEquals("我只是一个测试音乐源哦", header.description)
        assertEquals("1.0.0", header.version)
        assertEquals("xxx", header.author)
        assertEquals("https://example.com/source", header.homepage)
    }

    @Test
    fun `a bare header without leading asterisks is accepted too`() {
        // Both spellings occur in real sources; the doc shows the JSDoc one, but not every author writes it.
        val header = ok("/*\n@name 只有名字\n@version 2.0.0\n*/\nvar x = 1")
        assertEquals("只有名字", header.name)
        assertEquals("2.0.0", header.version)
    }

    @Test
    fun `optional fields may be absent`() {
        val header = ok("/**\n * @name 极简源\n */\nconst x = 1")
        assertEquals("极简源", header.name)
        assertNull(header.description)
        assertNull(header.version)
        assertNull(header.author)
        assertNull(header.homepage)
    }

    @Test
    fun `a byte order mark is not part of the header`() {
        val header = ok("\uFEFF" + valid)
        assertEquals("测试音乐源", header.name)
    }

    @Test
    fun `unknown keys inside the comment are ignored`() {
        val header = ok("/**\n * @name 源\n * @icon https://example.com/i.png\n * @note 说明\n */\nconst x = 1")
        assertEquals("源", header.name)
    }

    @Test
    fun `the first occurrence of a duplicated key wins`() {
        val header = ok("/**\n * @name 第一个\n * @name 第二个\n */\nconst x = 1")
        assertEquals("第一个", header.name)
    }

    @Test
    fun `a script with no leading comment is refused before anything runs`() {
        val error = fail("const a = 1")
        assertTrue(error.message, error.message.contains("脚本开头缺少自定义源信息注释"))
    }

    @Test
    fun `code before the header comment is not a header`() {
        // The doc requires the comment at the *beginning*; a `@name` after a statement is a coincidence.
        val error = fail("const a = 1\n/**\n * @name 迟到的名字\n */")
        assertTrue(error.message, error.message.contains("脚本开头缺少自定义源信息注释"))
    }

    @Test
    fun `an unterminated header comment is named as such`() {
        val error = fail("/**\n * @name 没有结束\n")
        assertTrue(error.message, error.message.contains("没有结束"))
    }

    @Test
    fun `a missing name is refused with the field named`() {
        val error = fail("/**\n * @version 1.0.0\n */\nconst x = 1")
        assertTrue(error.message, error.message.contains("@name"))
    }

    @Test
    fun `an empty script is refused`() {
        val error = fail("   \n\t ")
        assertTrue(error.message, error.message.contains("空"))
    }

    @Test
    fun `a name over the protocol limit is refused with both numbers`() {
        val name = "名".repeat(LxScriptHeaderParser.MAX_NAME_LENGTH + 1)
        val error = fail("/**\n * @name $name\n */\nconst x = 1")
        assertTrue(error.message, error.message.contains("${LxScriptHeaderParser.MAX_NAME_LENGTH}"))
        assertTrue(error.message, error.message.contains("${name.length}"))
    }

    @Test
    fun `a name exactly at the limit is accepted`() {
        val name = "名".repeat(LxScriptHeaderParser.MAX_NAME_LENGTH)
        assertEquals(name, ok("/**\n * @name $name\n */\nconst x = 1").name)
    }

    @Test
    fun `a description over the protocol limit is refused`() {
        val description = "描".repeat(LxScriptHeaderParser.MAX_DESCRIPTION_LENGTH + 1)
        val error = fail("/**\n * @name 源\n * @description $description\n */\nconst x = 1")
        assertTrue(error.message, error.message.contains("${LxScriptHeaderParser.MAX_DESCRIPTION_LENGTH}"))
    }

    private fun ok(script: String): com.yunyin.music.data.lxsource.LxScriptHeader =
        when (val result = LxScriptHeaderParser.parse(script)) {
            is LxScriptHeaderParser.Result.Ok -> result.header
            is LxScriptHeaderParser.Result.Error -> throw AssertionError("expected a header, got: ${result.message}")
        }

    private fun fail(script: String): LxScriptHeaderParser.Result.Error =
        when (val result = LxScriptHeaderParser.parse(script)) {
            is LxScriptHeaderParser.Result.Ok -> throw AssertionError("expected a failure, got: ${result.header}")
            is LxScriptHeaderParser.Result.Error -> result
        }
}
