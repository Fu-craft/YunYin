// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/** The search surface's mutually-exclusive presentations. */
internal enum class SearchPhase { Idle, Loading, Results, Empty, Error }

/**
 * Error is a state, not an empty result.
 *
 * A failed request used to clear the results and fall through to 「未找到…」, so a network failure read
 * as "this song does not exist". The rule is pure so the distinction cannot regress: an error wins
 * over the empty state, and a successful empty response is the only route to [SearchPhase.Empty].
 */
internal object SearchPhaseRules {
    fun phase(loading: Boolean, searched: Boolean, error: String?, resultCount: Int): SearchPhase = when {
        loading -> SearchPhase.Loading
        !searched -> SearchPhase.Idle
        resultCount > 0 -> SearchPhase.Results
        error != null -> SearchPhase.Error
        else -> SearchPhase.Empty
    }

    /** An append failure keeps the loaded rows and shows a retry footer instead of wiping them. */
    fun appendFailed(resultCount: Int, loadingMore: Boolean, error: String?): Boolean =
        resultCount > 0 && !loadingMore && error != null
}
