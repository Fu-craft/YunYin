// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/**
 * Tablet/foldable decisions. Tablets use the normal app shell: bottom navigation and mini player
 * stay, and Now Playing opens as a full-width page. A tabletop fold stacks the player; a vertical
 * hinge does not force a permanent side pane.
 */
object WindowLayoutRules {

    const val SPLIT_AT_DP = 840f
    const val CARD_COLUMNS_TABLET = 2

    fun isTablet(widthDp: Float): Boolean = widthDp >= SPLIT_AT_DP

    fun cardColumns(widthDp: Float): Int =
        if (isTablet(widthDp)) CARD_COLUMNS_TABLET else 1

    /** Tabletop (horizontal separating hinge) stacks; vertical hinges keep the full-width page. */
    fun isTabletop(horizontalSeparatingHinge: Boolean): Boolean = horizontalSeparatingHinge

    fun hingeGutterDp(hingeSizePx: Int, vertical: Boolean, density: Float): Float =
        if (vertical && hingeSizePx > 0) (hingeSizePx / density).coerceAtLeast(0f) else 0f
}
