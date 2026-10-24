// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.download

/**
 * Pure presentation decisions for the download centre.
 *
 * The page has two distinct shapes: a single empty state (no summary, no bulk-action row) and a content shape
 * (compact summary + actions + tabs). Keeping the decision here prevents the empty state from regressing into the
 * previous "summary card + three pill rows over nothing" layout.
 */
object DownloadCenterRules {
    fun isEmpty(active: Int, done: Int, failed: Int): Boolean = active == 0 && done == 0 && failed == 0

    fun showBulkActions(active: Int, done: Int, failed: Int): Boolean = !isEmpty(active, done, failed)
}
