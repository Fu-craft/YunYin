package com.yunyin.music.data

import com.yunyin.music.core.Comment
import com.yunyin.music.data.net.CommentPagingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comment paging: no duplicate rows across pages, bounded offset, no overlapping loads. */
class CommentPagingRulesTest {

    private fun comment(id: Long) = Comment(
        id = id, nickname = "u$id", avatarUrl = null, content = "c$id", likedCount = 0, timeMs = 0,
    )

    @Test
    fun `a page that overlaps the previous one does not duplicate rows`() {
        val merged = CommentPagingRules.appendUnique(
            listOf(comment(1), comment(2)),
            listOf(comment(2), comment(3)),
        )
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `the next offset exists only while the server says there is more`() {
        assertEquals(20, CommentPagingRules.nextOffset(0, 20, more = true))
        assertEquals(40, CommentPagingRules.nextOffset(20, 20, more = true))
        assertNull(CommentPagingRules.nextOffset(40, 20, more = false))
    }

    @Test
    fun `loading more is refused while a page is in flight or at the end`() {
        assertTrue(CommentPagingRules.canLoadMore(more = true, loading = false))
        assertFalse(CommentPagingRules.canLoadMore(more = true, loading = true))
        assertFalse(CommentPagingRules.canLoadMore(more = false, loading = false))
    }
}
