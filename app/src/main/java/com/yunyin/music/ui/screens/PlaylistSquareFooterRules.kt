// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/** What the 歌单广场 paging footer currently communicates. */
internal enum class SquareFooterPhase { Loading, Error, End }

/**
 * The footer used to swap instantly between a spinner, an error + retry and the end text, so a
 * paging tick read as a flicker. Keeping the decision pure makes the three states mutually exclusive
 * and testable: loading always wins, an error beats the "end" text, and "end" is the quiet default.
 */
internal object PlaylistSquareFooterRules {
    fun phase(loadingMore: Boolean, error: String?): SquareFooterPhase = when {
        loadingMore -> SquareFooterPhase.Loading
        error != null -> SquareFooterPhase.Error
        else -> SquareFooterPhase.End
    }
}
