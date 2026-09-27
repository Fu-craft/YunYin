package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Share text for a playlist.
 *
 * The rule being pinned: a link is only included when it actually resolves. A locally-built URL for
 * something with no public page would be worse than no link at all, because the recipient gets a
 * dead end rather than the intended content.
 */
class ShareTextTest {

    @Test
    fun `playlist with an id shares a resolvable page`() {
        val text = ShareText.playlist("单字一个赋喜欢的音乐", 12345L)
        assertEquals(
            "《单字一个赋喜欢的音乐》\nhttps://music.163.com/#/playlist?id=12345",
            text,
        )
    }

    @Test
    fun `liked songs has no public page so it carries no link`() {
        val text = ShareText.playlist("我喜欢的音乐", 0L)
        assertEquals("《我喜欢的音乐》", text)
        assertFalse("a link was built for an address that does not exist", text.contains("http"))
    }

    @Test
    fun `title and id both reach the link`() {
        val text = ShareText.playlist("Late Night", 987654321L)
        assertTrue(text.contains("Late Night"))
        assertTrue(text.endsWith("id=987654321"))
    }

    @Test
    fun `a blank title still produces something sendable`() {
        val text = ShareText.playlist("   ", 0L)
        assertFalse("empty share text", text.isBlank())
    }
}
