package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxHostUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * `lx.utils` is the part of the host where a mistake is silent: a hex/base64 mix-up returns *a* string, and a
 * zlib call with the wrong wrapper returns *bytes*, so nothing throws until a source fails with corrupt data.
 * These are plain JVM tests — no engine, no device — because the helpers are plain JVM code.
 */
class LxHostUtilitiesTest {

    @Test
    fun `utf8 survives a hex round trip, including non-ascii`() {
        val text = "测试 source：flac 24bit ünïcode"
        val hex = LxHostUtilities.utf8ToHex(text)
        assertEquals(text, LxHostUtilities.hexToUtf8(hex))
        // UTF-8 for "测" is e6 b5 8b, so the encoding is not accidentally UTF-16 or Latin-1.
        assertTrue(hex.startsWith("e6b58b"))
    }

    @Test
    fun `base64 and hex describe the same bytes`() {
        val hex = LxHostUtilities.utf8ToHex("hello")
        assertEquals("aGVsbG8=", LxHostUtilities.hexToBase64(hex))
        assertEquals(hex, LxHostUtilities.base64ToHex("aGVsbG8="))
    }

    @Test
    fun `md5 is the standard digest of the utf8 bytes`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", LxHostUtilities.md5Hex("hello"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LxHostUtilities.md5Hex(""))
    }

    @Test
    fun `random bytes returns the requested number of hex characters`() {
        assertEquals(0, LxHostUtilities.randomHex(0).length)
        assertEquals(32, LxHostUtilities.randomHex(16).length)
        assertEquals(64, LxHostUtilities.randomHex(32).length)
        assertTrue(LxHostUtilities.randomHex(16).all { it in "0123456789abcdef" })
        // Two draws must not be the same, or the source would generate a predictable "random" value.
        assertTrue(LxHostUtilities.randomHex(16) != LxHostUtilities.randomHex(16))
    }

    @Test
    fun `an unbounded random request is refused rather than allocated`() {
        val error = runCatching { LxHostUtilities.randomHex(LxHostUtilities.MAX_RANDOM_BYTES + 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `deflate and inflate round trip`() {
        val text = "歌词 lyric 1\n歌词 lyric 2\n"
        val compressed = LxHostUtilities.deflate(LxHostUtilities.utf8ToHex(text))
        // A real compression happened: the hex is shorter than the input's.
        assertTrue(compressed.length < LxHostUtilities.utf8ToHex(text).length)
        assertEquals(text, LxHostUtilities.hexToUtf8(LxHostUtilities.inflate(compressed)))
    }

    @Test
    fun `a gzip stream is detected and inflated`() {
        val text = "gzip payload"
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        assertEquals(text, LxHostUtilities.hexToUtf8(LxHostUtilities.inflate(gzipped.toHex())))
    }

    @Test
    fun `a raw deflate stream is inflated by the fallback`() {
        // Some APIs send `Content-Encoding: deflate` meaning raw Deflate without the zlib header; the
        // auto-detecting path is what makes both forms work.
        val text = "raw deflate payload"
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val raw = ByteArrayOutputStream().also { out ->
            deflater.setInput(text.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written <= 0) break
                out.write(buffer, 0, written)
            }
        }.toByteArray()
        deflater.end()
        assertEquals(text, LxHostUtilities.hexToUtf8(LxHostUtilities.inflate(raw.toHex())))
    }

    @Test
    fun `invalid hex is refused instead of decoded to nothing`() {
        assertTrue(runCatching { LxHostUtilities.hexToUtf8("xyz") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LxHostUtilities.hexToUtf8("abc") }.exceptionOrNull() is IllegalArgumentException)
    }

    /** `ByteArray.toHex` lives inside [LxHostUtilities]; tests build their own for arbitrary bytes. */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
