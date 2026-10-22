package com.yunyin.music.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTriggerRulesTest {

    @Test
    fun `near the end triggers a load`() {
        assertTrue(rule(lastVisibleIndex = 7, totalItemCount = 10))
        assertTrue(rule(lastVisibleIndex = 9, totalItemCount = 10))
    }

    @Test
    fun `the lookahead boundary is inclusive`() {
        assertTrue(rule(lastVisibleIndex = 7, totalItemCount = 10))
        assertFalse(rule(lastVisibleIndex = 6, totalItemCount = 10))
    }

    @Test
    fun `a list that fits on screen still triggers`() {
        // Last visible item is the final item: nothing to scroll, but the user is already at the end.
        assertTrue(rule(lastVisibleIndex = 2, totalItemCount = 3))
        assertTrue(rule(lastVisibleIndex = 0, totalItemCount = 1))
    }

    @Test
    fun `a page already loading blocks repeated loads`() {
        assertFalse(rule(lastVisibleIndex = 9, totalItemCount = 10, loadingMore = true))
    }

    @Test
    fun `the end of the list does not load`() {
        assertFalse(rule(lastVisibleIndex = 9, totalItemCount = 10, hasMore = false))
    }

    @Test
    fun `an append error waits for the explicit retry`() {
        assertFalse(rule(lastVisibleIndex = 9, totalItemCount = 10, appendError = true))
    }

    @Test
    fun `an empty list or missing first page does not load`() {
        assertFalse(rule(lastVisibleIndex = -1, totalItemCount = 0))
        assertFalse(rule(lastVisibleIndex = 0, totalItemCount = 1, hasItems = false))
    }

    @Test
    fun `the footer sentinel triggers when it becomes visible`() {
        assertTrue(
            PagingTriggerRules.shouldLoadAtFooter(
                footerVisible = true,
                hasMore = true,
                loadingMore = false,
                appendError = false,
                hasItems = true,
            ),
        )
        assertFalse(
            PagingTriggerRules.shouldLoadAtFooter(
                footerVisible = false,
                hasMore = true,
                loadingMore = false,
                appendError = false,
                hasItems = true,
            ),
        )
        assertFalse(
            PagingTriggerRules.shouldLoadAtFooter(
                footerVisible = true,
                hasMore = true,
                loadingMore = true,
                appendError = false,
                hasItems = true,
            ),
        )
    }

    private fun rule(
        lastVisibleIndex: Int,
        totalItemCount: Int,
        hasMore: Boolean = true,
        loadingMore: Boolean = false,
        appendError: Boolean = false,
        hasItems: Boolean = true,
    ): Boolean = PagingTriggerRules.shouldLoad(
        lastVisibleIndex = lastVisibleIndex,
        totalItemCount = totalItemCount,
        hasMore = hasMore,
        loadingMore = loadingMore,
        appendError = appendError,
        hasItems = hasItems,
    )
}
