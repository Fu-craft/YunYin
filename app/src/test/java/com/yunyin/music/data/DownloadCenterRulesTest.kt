// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.ui.download.DownloadCenterRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the download centre's empty-vs-content decision so the heavy header cannot come back on an empty page. */
class DownloadCenterRulesTest {

    @Test
    fun `all-zero counts are the single empty state`() {
        assertTrue(DownloadCenterRules.isEmpty(0, 0, 0))
        assertFalse(DownloadCenterRules.showBulkActions(0, 0, 0))
    }

    @Test
    fun `any task makes the compact summary and actions visible`() {
        assertFalse(DownloadCenterRules.isEmpty(1, 0, 0))
        assertFalse(DownloadCenterRules.isEmpty(0, 2, 0))
        assertFalse(DownloadCenterRules.isEmpty(0, 0, 3))
        assertTrue(DownloadCenterRules.showBulkActions(0, 1, 0))
    }
}
