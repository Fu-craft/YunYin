package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the search screen's 取消 rule: empty → Home, non-empty → clear. */
class SearchCancelRulesTest {

    @Test
    fun `an empty query dismisses search back to home`() {
        assertEquals(SearchCancelAction.ReturnHome, SearchCancelRules.actionFor(""))
        assertEquals(SearchCancelAction.ReturnHome, SearchCancelRules.actionFor("   "))
    }

    @Test
    fun `a non-empty query is cleared first`() {
        assertEquals(SearchCancelAction.ClearKeyword, SearchCancelRules.actionFor("周杰伦"))
        assertEquals(SearchCancelAction.ClearKeyword, SearchCancelRules.actionFor("a"))
    }
}
