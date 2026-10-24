// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the collection page's error-versus-empty contract.
 *
 * `CollectionUiState` had no error field, so a failed load rendered 「这个歌单还没有歌曲」. A failure
 * must win over empty; existing content must survive a transient refresh error; only a successful
 * empty response renders the empty state.
 */
class CollectionPhaseRulesTest {

    @Test
    fun `loading wins over everything`() {
        assertEquals(CollectionPhase.Loading, CollectionPhaseRules.phase(loading = true, error = "x", trackCount = 0))
        assertEquals(CollectionPhase.Loading, CollectionPhaseRules.phase(loading = true, error = null, trackCount = 5))
    }

    @Test
    fun `a failed empty load is an error, not an empty playlist`() {
        assertEquals(CollectionPhase.Error, CollectionPhaseRules.phase(loading = false, error = "加载失败", trackCount = 0))
    }

    @Test
    fun `a successful empty load is the empty state`() {
        assertEquals(CollectionPhase.Empty, CollectionPhaseRules.phase(loading = false, error = null, trackCount = 0))
    }

    @Test
    fun `existing rows are never replaced by a refresh error`() {
        assertEquals(CollectionPhase.Content, CollectionPhaseRules.phase(loading = false, error = "刷新失败", trackCount = 12))
        assertEquals(CollectionPhase.Content, CollectionPhaseRules.phase(loading = false, error = null, trackCount = 12))
    }
}
