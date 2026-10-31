// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui

import androidx.compose.ui.unit.dp

/**
 * Layout constants for large screens (tablets, foldables unfolded, desktop windows).
 *
 * The app was designed phone-first: one column, full-bleed shelves, a floating bottom bar. On a
 * 1000 dp-wide window that reads badly — rows stretch until the eye has to travel a metre, the tab
 * bar floats in the middle of nowhere, and the now-playing capsule becomes a cinema screen.
 *
 * These are the three numbers that fix it, kept in one place so every screen agrees:
 *
 * - [RAIL_WIDTH]: a persistent navigation rail replaces the bottom bar, so navigation is always
 *   visible and the bottom of the screen is free for the now-playing capsule.
 * - [CONTENT_MAX_WIDTH]: screens centre themselves inside this measure instead of stretching. 1040 dp
 *   is wide enough for two columns of cards and narrow enough that a line of text stays readable.
 * - [EXPANDED_WIDTH_DP]: the breakpoint. 840 dp is the standard "expanded" window class and matches
 *   an iPad in landscape / a typical 10" Android tablet.
 */
object TabletLayout {

    /** Expanded window class: rail navigation and a centred content measure. */
    const val EXPANDED_WIDTH_DP = 840f

    /** Medium class (small tablet, split screen): keep the bottom bar, but centre the measure. */
    const val MEDIUM_WIDTH_DP = 600f

    val RAIL_WIDTH = 92.dp
    val CONTENT_MAX_WIDTH = 1040.dp

    fun isExpanded(widthDp: Float): Boolean = widthDp >= EXPANDED_WIDTH_DP

    fun isMedium(widthDp: Float): Boolean = widthDp >= MEDIUM_WIDTH_DP

    /**
     * Horizontal padding that keeps content at [CONTENT_MAX_WIDTH] when the window is wider.
     *
     * Returned as a plain Float in dp so callers do not have to convert through Dp twice.
     */
    fun sideInsetDp(windowWidthDp: Float, railDp: Float): Float {
        val available = windowWidthDp - railDp
        val overflow = available - CONTENT_MAX_WIDTH.value
        return (overflow / 2f).coerceAtLeast(0f)
    }
}
