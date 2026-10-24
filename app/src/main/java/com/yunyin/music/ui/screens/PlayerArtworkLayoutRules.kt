// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical rhythm of the portrait artwork presentation (cover, title row and transport block).
 *
 * The reported defect: on a tall phone there was a dead band above the cover and a second one between
 * the cover and the title. The cause was structural, not a bad constant: the artwork lived in a
 * `weight(1f)` region while its size was capped by the *width* (a square cover), so every extra
 * point of screen height became empty space inside that region — half above and below the cover in
 * practice, all of it piled up between the cover and the title row because the region aligned the
 * cover to its top. A fixed `56.dp` spacer on top made the first band worse.
 *
 * The rules below replace that with a layout whose remaining space has nowhere to leak into the
 * composition's rhythm:
 *
 *  - the artwork region is `weight(1f, fill = false)`, so it is measured against the height left
 *    after the title row and the transport block are laid out — extra screen height lands *below the
 *    controls*, never between the cover and its title;
 *  - the cover is the width fraction or that remaining height, whichever is smaller, so a short
 *    screen shrinks the cover instead of clipping the controls;
 *  - the two spacing values scale with the available height and are clamped **tight** (8–14 dp
 *    above the cover, 10–14 dp cover→title): the title row should read as attached to the cover, as
 *    in Apple Music. A small phone stays compact without the gaps collapsing; a tall phone gets a
 *    point or two more air rather than the dead band this layout used to have.
 *
 * Pure and unit-tested ([PlayerArtworkLayoutRulesTest]) because "balanced across screen sizes" is
 * exactly the kind of rule that silently drifts when it lives as three magic numbers in a layout.
 */
internal object PlayerArtworkLayoutRules {

    /** Share of the available width the square artwork occupies (Apple Music sits near 0.9). */
    const val ARTWORK_WIDTH_FRACTION = 0.90f

    /** Breathing room above the cover: a share of the available height, clamped to a tight band. */
    fun topInset(availableHeight: Dp): Dp =
        (availableHeight * 0.012f).coerceIn(8.dp, 14.dp)

    /** Gap between the cover and the title row: a share of the available height, clamped tight. */
    fun coverTitleGap(availableHeight: Dp): Dp =
        (availableHeight * 0.014f).coerceIn(10.dp, 14.dp)

    /**
     * Edge length of the square cover: the width fraction, or the height left over after the title
     * and transport blocks when that is smaller. Never negative, so a short screen can only make the
     * artwork smaller — it can never push the controls off the bottom.
     */
    fun artworkSize(availableWidth: Dp, remainingHeight: Dp): Dp =
        minOf(availableWidth * ARTWORK_WIDTH_FRACTION, remainingHeight).coerceAtLeast(0.dp)
}
