// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the paging footer's single, mutually-exclusive phase. */
class PlaylistSquareFooterRulesTest {

    @Test
    fun `an in-flight append shows the spinner even if a previous error is stale`() {
        assertEquals(
            SquareFooterPhase.Loading,
            PlaylistSquareFooterRules.phase(loadingMore = true, error = "旧错误"),
        )
    }

    @Test
    fun `an append error wins over the end text`() {
        assertEquals(
            SquareFooterPhase.Error,
            PlaylistSquareFooterRules.phase(loadingMore = false, error = "网络异常"),
        )
    }

    @Test
    fun `with neither a load nor an error the catalogue is simply exhausted`() {
        assertEquals(
            SquareFooterPhase.End,
            PlaylistSquareFooterRules.phase(loadingMore = false, error = null),
        )
    }
}
