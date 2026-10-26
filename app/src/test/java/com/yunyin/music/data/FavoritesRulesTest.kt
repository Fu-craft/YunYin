// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.data.FavoritesRules.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRulesTest {

    @Test
    fun `favourites empty and paging decisions are stable`() {
        assertTrue(FavoritesRules.emptyText(Segment.Artists, true).contains("歌手"))
        assertEquals(
            "登录后可在这里查看收藏的歌手、专辑和歌单",
            FavoritesRules.emptyText(Segment.Albums, false),
        )
        assertEquals(listOf(1L, 2L, 3L), FavoritesRules.mergeDistinct(listOf(1L, 2L), listOf(2L, 3L)))
        assertTrue(FavoritesRules.shouldRequest(true, false, true, false))
        assertFalse(FavoritesRules.shouldRequest(false, false, true, false))
        assertFalse(FavoritesRules.shouldRequest(true, true, true, false))
        assertFalse(FavoritesRules.shouldRequest(true, false, false, false))
        assertFalse(FavoritesRules.shouldRequest(true, false, true, true))
    }
}
