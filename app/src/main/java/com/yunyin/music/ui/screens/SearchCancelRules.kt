// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/** What the search screen's 取消 button does for the current query. */
enum class SearchCancelAction {
    /** Query is non-empty: keep the current behaviour and clear it. */
    ClearKeyword,

    /** Query is empty: dismiss search and return to Home. */
    ReturnHome,
}

/**
 * 取消 rule for the search screen.
 *
 * Empty query → home: the search screen is Home's child, so with nothing typed the only sensible
 * action is to dismiss it. Non-empty → clear first: the common intent while typing is to start over,
 * and dismissing the whole screen on the first tap would destroy the field for a typo fix. The
 * second tap (now empty) returns home, so the user is never stuck.
 *
 * Pure and unit-tested (`SearchCancelRulesTest`).
 */
object SearchCancelRules {
    fun actionFor(keyword: String): SearchCancelAction =
        if (keyword.isBlank()) SearchCancelAction.ReturnHome else SearchCancelAction.ClearKeyword
}
