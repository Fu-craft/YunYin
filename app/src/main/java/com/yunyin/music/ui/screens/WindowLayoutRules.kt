// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

/** Pure two-pane decisions for tablets and foldables. */
object WindowLayoutRules {

    const val SPLIT_AT_DP = 840f
    const val NAV_RAIL_DP = 84f

    fun isTwoPane(widthDp: Float): Boolean = widthDp >= SPLIT_AT_DP

    /** Half the usable width after reserving the hinge gutter. */
    fun leftPaneWidthDp(widthDp: Float, hingeDp: Float): Float =
        ((widthDp - hingeDp).coerceAtLeast(0f) / 2f).coerceAtLeast(0f)

    fun hingeGutterDp(hingeWidthPx: Int, vertical: Boolean, density: Float): Float =
        if (vertical && hingeWidthPx > 0) (hingeWidthPx / density).coerceAtLeast(0f) else 0f
}
