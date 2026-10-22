package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the search surface's error-versus-empty contract.
 *
 * Before this rule a failed request cleared the results and fell through to 「未找到…」, so a network
 * failure looked like "this song does not exist". An error must win over empty; only a successful
 * empty response may render the empty state; an append failure keeps the loaded rows.
 */
class SearchPhaseRulesTest {

    @Test
    fun `loading wins over everything`() {
        assertEquals(SearchPhase.Loading, SearchPhaseRules.phase(loading = true, searched = true, error = "boom", resultCount = 3))
        assertEquals(SearchPhase.Loading, SearchPhaseRules.phase(loading = true, searched = true, error = null, resultCount = 0))
    }

    @Test
    fun `an untouched search is idle`() {
        assertEquals(SearchPhase.Idle, SearchPhaseRules.phase(loading = false, searched = false, error = null, resultCount = 0))
    }

    @Test
    fun `a failed request is an error, never empty`() {
        assertEquals(SearchPhase.Error, SearchPhaseRules.phase(loading = false, searched = true, error = "网络异常", resultCount = 0))
    }

    @Test
    fun `a successful empty response is the only route to empty`() {
        assertEquals(SearchPhase.Empty, SearchPhaseRules.phase(loading = false, searched = true, error = null, resultCount = 0))
    }

    @Test
    fun `existing results stay visible even when an error is present`() {
        // An append failure: the rows are kept and the footer turns into a retry.
        assertEquals(SearchPhase.Results, SearchPhaseRules.phase(loading = false, searched = true, error = "加载更多失败", resultCount = 20))
    }

    @Test
    fun `append failure is only a footer state when rows exist and nothing is in flight`() {
        assertTrue(SearchPhaseRules.appendFailed(resultCount = 20, loadingMore = false, error = "x"))
        assertFalse(SearchPhaseRules.appendFailed(resultCount = 0, loadingMore = false, error = "x"))
        assertFalse(SearchPhaseRules.appendFailed(resultCount = 20, loadingMore = true, error = "x"))
        assertFalse(SearchPhaseRules.appendFailed(resultCount = 20, loadingMore = false, error = null))
    }
}
