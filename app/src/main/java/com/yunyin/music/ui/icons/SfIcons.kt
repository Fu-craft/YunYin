// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-authored SF Symbols equivalents.
 *
 * Rather than Material icons, every glyph here is drawn to match the shape language of
 * Apple's SF Symbols: optically centered on a 24pt grid, filled or monoline-stroked with
 * fully rounded terminals, and sized to sit on an 8pt spacing rhythm. Names mirror the
 * SF Symbols identifiers (`play.fill`, `heart`, `chevron.left`, …) so the call sites read
 * the same way they would in SwiftUI.
 *
 * Icons are declared with a neutral color because [androidx.compose.material3.Icon]
 * applies its own tint over the vector.
 */
object SfIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** Monoline stroke with the round caps/joins that give SF its softness. */
    private fun ImageVector.Builder.mono(
        name: String,
        width: Float = 2.1f,
        pathBuilder: PathBuilder.() -> Unit,
    ): ImageVector.Builder = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        name = name,
        pathBuilder = pathBuilder,
    )

    /** Rounded-rectangle sub-path; the shared corner maths of the grid-style glyphs. */
    private fun PathBuilder.roundedRect(x: Float, y: Float, size: Float, radius: Float) {
        val r = radius.coerceAtMost(size / 2f)
        val k = r * 0.5523f
        moveTo(x + r, y)
        lineTo(x + size - r, y)
        curveTo(x + size - r + k, y, x + size, y + r - k, x + size, y + r)
        lineTo(x + size, y + size - r)
        curveTo(x + size, y + size - r + k, x + size - r + k, y + size, x + size - r, y + size)
        lineTo(x + r, y + size)
        curveTo(x + r - k, y + size, x, y + size - r + k, x, y + size - r)
        lineTo(x, y + r)
        curveTo(x, y + r - k, x + r - k, y, x + r, y)
        close()
    }

    // ------------------------------------------------------------------ transport

    val Play: ImageVector = icon("play.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.6f, 5.05f)
            lineTo(18.6f, 11.09f)
            curveTo(19.35f, 11.5f, 19.35f, 12.5f, 18.6f, 12.91f)
            lineTo(7.6f, 18.95f)
            curveTo(6.87f, 19.35f, 6.0f, 18.83f, 6.0f, 18.04f)
            verticalLineTo(5.96f)
            curveTo(6.0f, 5.17f, 6.87f, 4.65f, 7.6f, 5.05f)
            close()
        }
    }

    val Pause: ImageVector = icon("pause.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.1f, 4.4f)
            curveTo(8.05f, 4.4f, 8.8f, 5.15f, 8.8f, 6.1f)
            verticalLineTo(17.9f)
            curveTo(8.8f, 18.85f, 8.05f, 19.6f, 7.1f, 19.6f)
            curveTo(6.15f, 19.6f, 5.4f, 18.85f, 5.4f, 17.9f)
            verticalLineTo(6.1f)
            curveTo(5.4f, 5.15f, 6.15f, 4.4f, 7.1f, 4.4f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.9f, 4.4f)
            curveTo(17.85f, 4.4f, 18.6f, 5.15f, 18.6f, 6.1f)
            verticalLineTo(17.9f)
            curveTo(18.6f, 18.85f, 17.85f, 19.6f, 16.9f, 19.6f)
            curveTo(15.95f, 19.6f, 15.2f, 18.85f, 15.2f, 17.9f)
            verticalLineTo(6.1f)
            curveTo(15.2f, 5.15f, 15.95f, 4.4f, 16.9f, 4.4f)
            close()
        }
    }

    /** `backward.fill` — two rounded triangles pointing left. */
    val Backward: ImageVector = icon("backward.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.05f, 6.55f)
            lineTo(4.83f, 11.28f)
            curveTo(4.28f, 11.65f, 4.28f, 12.35f, 4.83f, 12.72f)
            lineTo(12.05f, 17.45f)
            curveTo(12.6f, 17.81f, 13.3f, 17.44f, 13.3f, 16.73f)
            verticalLineTo(7.27f)
            curveTo(13.3f, 6.56f, 12.6f, 6.19f, 12.05f, 6.55f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.15f, 6.55f)
            lineTo(12.93f, 11.28f)
            curveTo(12.38f, 11.65f, 12.38f, 12.35f, 12.93f, 12.72f)
            lineTo(20.15f, 17.45f)
            curveTo(20.7f, 17.81f, 21.4f, 17.44f, 21.4f, 16.73f)
            verticalLineTo(7.27f)
            curveTo(21.4f, 6.56f, 20.7f, 6.19f, 20.15f, 6.55f)
            close()
        }
    }

    /** `forward.fill` — two rounded triangles pointing right. */
    val Forward: ImageVector = icon("forward.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.95f, 6.55f)
            lineTo(19.17f, 11.28f)
            curveTo(19.72f, 11.65f, 19.72f, 12.35f, 19.17f, 12.72f)
            lineTo(11.95f, 17.45f)
            curveTo(11.4f, 17.81f, 10.7f, 17.44f, 10.7f, 16.73f)
            verticalLineTo(7.27f)
            curveTo(10.7f, 6.56f, 11.4f, 6.19f, 11.95f, 6.55f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.85f, 6.55f)
            lineTo(11.07f, 11.28f)
            curveTo(11.62f, 11.65f, 11.62f, 12.35f, 11.07f, 12.72f)
            lineTo(3.85f, 17.45f)
            curveTo(3.3f, 17.81f, 2.6f, 17.44f, 2.6f, 16.73f)
            verticalLineTo(7.27f)
            curveTo(2.6f, 6.56f, 3.3f, 6.19f, 3.85f, 6.55f)
            close()
        }
    }

    // ------------------------------------------------------------------ symbols

    /**
     * Heart, monoline — the 心动模式 bar entry.
     *
     * The 4.10.0 redraw widened the lobes to the family's 19 pt optical box (path 3.4–20.6, stroke
     * 1.9 → visible 2.45–21.55) and pulled the tip in to 20.2, so at 28 dp the mark reads full and
     * rounded instead of narrow. The lobe/tip curves are one continuous outline: no pinch survives.
     */
    val Heart: ImageVector = icon("heart") {
        mono("heart", width = 1.9f) {
            moveTo(12f, 20.2f)
            curveTo(12f, 20.2f, 3.4f, 14.8f, 3.4f, 8.9f)
            curveTo(3.4f, 6.1f, 5.6f, 3.9f, 8.4f, 3.9f)
            curveTo(10.2f, 3.9f, 11.3f, 4.9f, 12f, 5.8f)
            curveTo(12.7f, 4.9f, 13.8f, 3.9f, 15.6f, 3.9f)
            curveTo(18.4f, 3.9f, 20.6f, 6.1f, 20.6f, 8.9f)
            curveTo(20.6f, 14.8f, 12f, 20.2f, 12f, 20.2f)
            close()
        }
    }

    /**
     * Filled heart: the selected-state mark, in the family's fill box (2.95-21.05 in both axes) —
     * the outline's geometry grown by the stroke, so selecting 心动模式 does not change its size.
     */
    val HeartFill: ImageVector = icon("heart.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 21.05f)
            curveTo(12f, 21.05f, 2.95f, 14.7f, 2.95f, 8.8f)
            curveTo(2.95f, 5.7f, 5.45f, 2.95f, 8.6f, 2.95f)
            curveTo(10.45f, 2.95f, 11.5f, 4.0f, 12f, 4.95f)
            curveTo(12.5f, 4.0f, 13.55f, 2.95f, 15.4f, 2.95f)
            curveTo(18.55f, 2.95f, 21.05f, 5.7f, 21.05f, 8.8f)
            curveTo(21.05f, 14.7f, 12f, 21.05f, 12f, 21.05f)
            close()
        }
    }

    val Ellipsis: ImageVector = icon("ellipsis") {
        listOf(5.4f, 12f, 18.6f).forEach { cx ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(cx + 1.75f, 12f)
                curveTo(cx + 1.75f, 12.97f, cx + 0.97f, 13.75f, cx, 13.75f)
                curveTo(cx - 0.97f, 13.75f, cx - 1.75f, 12.97f, cx - 1.75f, 12f)
                curveTo(cx - 1.75f, 11.03f, cx - 0.97f, 10.25f, cx, 10.25f)
                curveTo(cx + 0.97f, 10.25f, cx + 1.75f, 11.03f, cx + 1.75f, 12f)
                close()
            }
        }
    }

    val MagnifyingGlass: ImageVector = icon("magnifyingglass") {
        mono("glass", width = 2.2f) {
            moveTo(16.2f, 10.6f)
            curveTo(16.2f, 13.7f, 13.7f, 16.2f, 10.6f, 16.2f)
            curveTo(7.5f, 16.2f, 5f, 13.7f, 5f, 10.6f)
            curveTo(5f, 7.5f, 7.5f, 5f, 10.6f, 5f)
            curveTo(13.7f, 5f, 16.2f, 7.5f, 16.2f, 10.6f)
            close()
        }
        mono("handle", width = 2.2f) {
            moveTo(14.75f, 14.75f)
            lineTo(19.2f, 19.2f)
        }
    }

    /**
     * House, monoline — 主页.
     *
     * The same family grammar as the heart and the person: a soft arch for the roof apex, small
     * radius curves where the roof meets the walls, and rounded base corners — no sharp triangle.
     * Its path box is 3.4-20.6 × 3.9-20.6, exactly the other outlines' box, so the four resting
     * marks share one optical size. The arched door is an outline opening, which is what keeps the
     * glyph recognisable as a house at 28 dp. The bar draws this outline in both states — it never
     * swaps in a filled mark.
     */
    val House: ImageVector = icon("house") {
        mono("house", width = 1.9f) {
            moveTo(11.35f, 4.2f)
            curveTo(11.7f, 3.9f, 12.3f, 3.9f, 12.65f, 4.2f)
            lineTo(20.25f, 10.35f)
            curveTo(20.5f, 10.55f, 20.6f, 10.8f, 20.6f, 11.1f)
            lineTo(20.6f, 19.0f)
            curveTo(20.6f, 19.9f, 19.9f, 20.6f, 19.0f, 20.6f)
            lineTo(5.0f, 20.6f)
            curveTo(4.1f, 20.6f, 3.4f, 19.9f, 3.4f, 19.0f)
            lineTo(3.4f, 11.1f)
            curveTo(3.4f, 10.8f, 3.5f, 10.55f, 3.75f, 10.35f)
            close()
        }
        mono("house.door", width = 1.9f) {
            moveTo(9.65f, 20.6f)
            lineTo(9.65f, 16.9f)
            curveTo(9.65f, 15.62f, 10.7f, 14.55f, 12f, 14.55f)
            curveTo(13.3f, 14.55f, 14.35f, 15.62f, 14.35f, 16.9f)
            lineTo(14.35f, 20.6f)
        }
    }

    val Person: ImageVector = icon("person.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 12.6f)
            curveTo(14.54f, 12.6f, 16.6f, 10.54f, 16.6f, 8f)
            curveTo(16.6f, 5.46f, 14.54f, 3.4f, 12f, 3.4f)
            curveTo(9.46f, 3.4f, 7.4f, 5.46f, 7.4f, 8f)
            curveTo(7.4f, 10.54f, 9.46f, 12.6f, 12f, 12.6f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(4.2f, 19.2f)
            curveTo(4.2f, 15.95f, 7.66f, 14.2f, 12f, 14.2f)
            curveTo(16.34f, 14.2f, 19.8f, 15.95f, 19.8f, 19.2f)
            curveTo(19.8f, 20.19f, 19.0f, 21.0f, 18f, 21.0f)
            horizontalLineTo(6f)
            curveTo(5.0f, 21.0f, 4.2f, 20.19f, 4.2f, 19.2f)
            close()
        }
    }

    /**
     * Person, monoline — 资料库.
     *
     * The 4.10.0 redraw gives the head radius 3.8 (was 3.4) and a rounder, wider shoulder arc
     * (3.6–20.4), so at 28 dp the bust carries the same weight as the house and grid instead of
     * reading as a small head over a thin line. The bar draws this outline in both states.
     */
    val PersonOutline: ImageVector = icon("person") {
        // The bar's 资料库 glyph: monoline like house and the playlist grid. The filled `Person`
        // stays for avatar placeholders, where a silhouette reads better than an outline.
        mono("person.head", width = 1.9f) {
            moveTo(12f, 4.1f)
            curveTo(14.1f, 4.1f, 15.8f, 5.8f, 15.8f, 7.9f)
            curveTo(15.8f, 10.0f, 14.1f, 11.7f, 12f, 11.7f)
            curveTo(9.9f, 11.7f, 8.2f, 10.0f, 8.2f, 7.9f)
            curveTo(8.2f, 5.8f, 9.9f, 4.1f, 12f, 4.1f)
            close()
        }
        mono("person.shoulders", width = 1.9f) {
            moveTo(3.4f, 20.6f)
            curveTo(4.2f, 16.0f, 7.8f, 13.8f, 12f, 13.8f)
            curveTo(16.2f, 13.8f, 19.8f, 16.0f, 20.6f, 20.6f)
        }
    }

    val ChevronLeft: ImageVector = icon("chevron.left") {
        mono("chevron.left", width = 2.4f) {
            moveTo(15f, 4.8f)
            lineTo(8f, 12f)
            lineTo(15f, 19.2f)
        }
    }

    val ChevronRight: ImageVector = icon("chevron.right") {
        mono("chevron.right", width = 2.4f) {
            moveTo(9f, 4.8f)
            lineTo(16f, 12f)
            lineTo(9f, 19.2f)
        }
    }

    val ChevronDown: ImageVector = icon("chevron.down") {
        mono("chevron.down", width = 2.4f) {
            moveTo(4.8f, 9f)
            lineTo(12f, 16f)
            lineTo(19.2f, 9f)
        }
    }

    val Xmark: ImageVector = icon("xmark") {
        mono("xmark", width = 2.3f) {
            moveTo(6.2f, 6.2f)
            lineTo(17.8f, 17.8f)
            moveTo(17.8f, 6.2f)
            lineTo(6.2f, 17.8f)
        }
    }

    val Plus: ImageVector = icon("plus") {
        mono("plus", width = 2.3f) {
            moveTo(12f, 5.4f)
            verticalLineTo(18.6f)
            moveTo(5.4f, 12f)
            horizontalLineTo(18.6f)
        }
    }

    val Checkmark: ImageVector = icon("checkmark") {
        mono("checkmark", width = 2.4f) {
            moveTo(4.8f, 12.6f)
            lineTo(9.8f, 17.4f)
            lineTo(19.2f, 6.6f)
        }
    }

    /** `quote.opening` — the mood/annotation control on the player. */
    val QuoteOpening: ImageVector = icon("quote.opening") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5.1f, 15.2f)
            curveTo(4.0f, 14.7f, 3.4f, 13.6f, 3.4f, 12.1f)
            curveTo(3.4f, 9.1f, 5.4f, 6.4f, 8.6f, 5.1f)
            lineTo(9.6f, 6.6f)
            curveTo(7.6f, 7.6f, 6.4f, 9.0f, 6.1f, 10.4f)
            curveTo(7.0f, 10.4f, 7.8f, 11.2f, 7.8f, 12.3f)
            curveTo(7.8f, 13.5f, 6.9f, 14.4f, 5.7f, 14.4f)
            curveTo(5.5f, 14.4f, 5.3f, 14.4f, 5.1f, 15.2f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.6f, 15.2f)
            curveTo(13.5f, 14.7f, 12.9f, 13.6f, 12.9f, 12.1f)
            curveTo(12.9f, 9.1f, 14.9f, 6.4f, 18.1f, 5.1f)
            lineTo(19.1f, 6.6f)
            curveTo(17.1f, 7.6f, 15.9f, 9.0f, 15.6f, 10.4f)
            curveTo(16.5f, 10.4f, 17.3f, 11.2f, 17.3f, 12.3f)
            curveTo(17.3f, 13.5f, 16.4f, 14.4f, 15.2f, 14.4f)
            curveTo(15.0f, 14.4f, 14.8f, 14.4f, 14.6f, 15.2f)
            close()
        }
    }

    /** `bubble.left` — comments, so the 评论 row is no longer a second heart. */
    val BubbleLeft: ImageVector = icon("bubble.left") {
        mono("bubble.left", width = 1.9f) {
            moveTo(12f, 4.0f)
            curveTo(7.4f, 4.0f, 3.7f, 7.0f, 3.7f, 10.8f)
            curveTo(3.7f, 13.0f, 4.9f, 14.9f, 6.9f, 16.2f)
            lineTo(6.1f, 20.2f)
            lineTo(10.4f, 17.7f)
            curveTo(10.9f, 17.8f, 11.5f, 17.8f, 12f, 17.8f)
            curveTo(16.6f, 17.8f, 20.3f, 14.8f, 20.3f, 10.8f)
            curveTo(20.3f, 7.0f, 16.6f, 4.0f, 12f, 4.0f)
            close()
        }
    }

    /**
     * `repeat` — the loop control's off/all state.
     *
     * A rounded rectangle broken only at the top-right and bottom-left, where the two arrowheads
     * sit. The break is what makes it read as a loop with a direction rather than as a plain rounded
     * box, and confining it to those two corners is what keeps the outline legible as one continuous
     * shape.
     *
     * The previous shape was drawn as two separate arcs with big solid triangular heads and its two
     * halves never joined up, so it rendered as two disconnected arrows pointing at each other — it
     * did not read as a loop at all. Three things fix that: the corners are real quarter-circles, the
     * verticals and horizontals run the full side so the loop stays continuous, and the heads are
     * chevrons at the loop's own stroke weight rather than solid triangles, which is how SF Symbols
     * draws them and what stops them dominating a 26dp glyph.
     *
     * The box is a little wider than it is tall (14×11.6 units) rather than square: the two arrowheads
     * project above and below it, so a square box would make the finished glyph read as too tall.
     */
    val Repeat: ImageVector = icon("repeat") {
        mono("repeat.right") {
            moveTo(5.0f, 15.0f)
            verticalLineTo(9.0f)
            curveTo(5.0f, 7.45f, 6.25f, 6.2f, 7.8f, 6.2f)
            horizontalLineTo(16.6f)
        }
        mono("repeat.right.tip") {
            moveTo(14.4f, 4.0f)
            lineTo(16.6f, 6.2f)
            lineTo(14.4f, 8.4f)
        }
        mono("repeat.left") {
            moveTo(19.0f, 9.0f)
            verticalLineTo(15.0f)
            curveTo(19.0f, 16.55f, 17.75f, 17.8f, 16.2f, 17.8f)
            horizontalLineTo(7.4f)
        }
        mono("repeat.left.tip") {
            moveTo(9.6f, 15.6f)
            lineTo(7.4f, 17.8f)
            lineTo(9.6f, 20.0f)
        }
    }

    /**
     * `repeat.1` — the loop control's repeat-one state.
     *
     * The same loop with a numeral in the middle, which is the only thing that can tell repeat-one
     * from repeat-all: both are "active", so the tint cannot distinguish them.
     *
     * The numeral is a stem with a short flag and a base. The base is not decoration — at the ~5dp a
     * numeral gets inside this loop, a bare stem reads as a divider and the flag alone reads as a
     * slash. It is struck thinner than the loop so the two do not merge, and it sits on the loop's
     * centre with clear space on every side (the loop was widened for exactly this).
     */
    val RepeatOne: ImageVector = icon("repeat.1") {
        mono("repeat.1.loop.right") {
            moveTo(5.0f, 15.0f)
            verticalLineTo(9.0f)
            curveTo(5.0f, 7.45f, 6.25f, 6.2f, 7.8f, 6.2f)
            horizontalLineTo(16.6f)
        }
        mono("repeat.1.loop.right.tip") {
            moveTo(14.4f, 4.0f)
            lineTo(16.6f, 6.2f)
            lineTo(14.4f, 8.4f)
        }
        mono("repeat.1.loop.left") {
            moveTo(19.0f, 9.0f)
            verticalLineTo(15.0f)
            curveTo(19.0f, 16.55f, 17.75f, 17.8f, 16.2f, 17.8f)
            horizontalLineTo(7.4f)
        }
        mono("repeat.1.loop.left.tip") {
            moveTo(9.6f, 15.6f)
            lineTo(7.4f, 17.8f)
            lineTo(9.6f, 20.0f)
        }
        mono("repeat.1.numeral", width = 1.7f) {
            moveTo(11.2f, 10.75f)
            lineTo(12.6f, 9.7f)
            verticalLineTo(14.3f)
            moveTo(11.4f, 14.3f)
            horizontalLineTo(13.8f)
        }
    }

    /**
     * `shuffle` — two lanes swapping sides.
     *
     * Rebuilt because the previous version crowded its two curves together near the middle and ended
     * each in a heavy solid triangle, so it read as an X with wedges rather than as two crossing
     * arrows. The lanes are now set wide apart (y 8.2 and 15.8) and cross exactly at the centre,
     * which is the symmetry the symbol depends on, and the heads are slim chevrons.
     */
    val Shuffle: ImageVector = icon("shuffle") {
        mono("shuffle.down") {
            moveTo(3.2f, 8.2f)
            horizontalLineTo(6.4f)
            curveTo(10.6f, 8.2f, 11.6f, 15.8f, 15.8f, 15.8f)
            horizontalLineTo(18.6f)
        }
        mono("shuffle.down.tip") {
            moveTo(16.2f, 13.4f)
            lineTo(18.6f, 15.8f)
            lineTo(16.2f, 18.2f)
        }
        mono("shuffle.up") {
            moveTo(3.2f, 15.8f)
            horizontalLineTo(6.4f)
            curveTo(10.6f, 15.8f, 11.6f, 8.2f, 15.8f, 8.2f)
            horizontalLineTo(18.6f)
        }
        mono("shuffle.up.tip") {
            moveTo(16.2f, 5.8f)
            lineTo(18.6f, 8.2f)
            lineTo(16.2f, 10.6f)
        }
    }

    /** `list.bullet` — the queue control on the player. */
    val ListBullet: ImageVector = icon("list.bullet") {
        listOf(6.6f, 12f, 17.4f).forEach { y ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.15f, y)
                curveTo(6.15f, y + 0.99f, 5.35f, y + 1.79f, 4.36f, y + 1.79f)
                curveTo(3.37f, y + 1.79f, 2.57f, y + 0.99f, 2.57f, y)
                curveTo(2.57f, y - 0.99f, 3.37f, y - 1.79f, 4.36f, y - 1.79f)
                curveTo(5.35f, y - 1.79f, 6.15f, y - 0.99f, 6.15f, y)
                close()
            }
        }
        mono("list.bullet.lines", width = 2.0f) {
            moveTo(10.3f, 6.6f)
            horizontalLineTo(20.6f)
            moveTo(10.3f, 12f)
            horizontalLineTo(20.6f)
            moveTo(10.3f, 17.4f)
            horizontalLineTo(20.6f)
        }
    }

    /** `star` 鈥?the Apple Music favourite action, monoline like the screenshot. */
    val Star: ImageVector = icon("star") {
        mono("star", width = 1.9f) {
            moveTo(12f, 3.2f)
            lineTo(14.7f, 9.0f)
            lineTo(21.0f, 9.85f)
            lineTo(16.4f, 14.25f)
            lineTo(17.55f, 20.5f)
            lineTo(12f, 17.5f)
            lineTo(6.45f, 20.5f)
            lineTo(7.6f, 14.25f)
            lineTo(3.0f, 9.85f)
            lineTo(9.3f, 9.0f)
            close()
        }
    }

    val StarFill: ImageVector = icon("star.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3.2f)
            lineTo(14.7f, 9.0f)
            lineTo(21.0f, 9.85f)
            lineTo(16.4f, 14.25f)
            lineTo(17.55f, 20.5f)
            lineTo(12f, 17.5f)
            lineTo(6.45f, 20.5f)
            lineTo(7.6f, 14.25f)
            lineTo(3.0f, 9.85f)
            lineTo(9.3f, 9.0f)
            close()
        }
    }

    /**
     * `folder.music` — 本地音乐. A folder (local files) with a small note inside, drawn monoline on the same
     * 24 pt grid / 1.9 pt stroke / round-cap construction as [SquareAndArrowDown] and [ListBullet]. The guide
     * for the folder came from Lucide's `folder-music` (ISC, MIT-compatible); re-drawn here rather than imported.
     */
    val FolderMusic: ImageVector = icon("folder.music") {
        mono("folder.music.body", width = 1.9f) {
            moveTo(3f, 7.4f)
            curveTo(3f, 6.3f, 3.9f, 5.4f, 5f, 5.4f)
            horizontalLineTo(9.1f)
            lineTo(11.2f, 7.4f)
            horizontalLineTo(19f)
            curveTo(20.1f, 7.4f, 21f, 8.3f, 21f, 9.4f)
            verticalLineTo(17.8f)
            curveTo(21f, 18.9f, 20.1f, 19.8f, 19f, 19.8f)
            horizontalLineTo(5f)
            curveTo(3.9f, 19.8f, 3f, 18.9f, 3f, 17.8f)
            close()
        }
        mono("folder.music.note", width = 1.9f) {
            moveTo(15.6f, 11.1f)
            verticalLineTo(15.9f)
            lineTo(18.2f, 15.3f)
            verticalLineTo(10.6f)
        }
        mono("folder.music.head", width = 1.9f) {
            moveTo(13.5f, 16.1f)
            curveTo(13.5f, 15.3f, 14.2f, 14.7f, 15.0f, 14.7f)
            curveTo(15.8f, 14.7f, 16.5f, 15.3f, 16.5f, 16.1f)
            curveTo(16.5f, 16.9f, 15.8f, 17.5f, 15.0f, 17.5f)
            curveTo(14.2f, 17.5f, 13.5f, 16.9f, 13.5f, 16.1f)
            close()
        }
    }

    val MusicNote: ImageVector = icon("music.note") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.2f, 18.1f)
            curveTo(7.5f, 18.1f, 6.1f, 17.0f, 6.1f, 15.5f)
            curveTo(6.1f, 14.0f, 7.5f, 12.9f, 9.2f, 12.9f)
            curveTo(9.9f, 12.9f, 10.6f, 13.1f, 11.1f, 13.45f)
            verticalLineTo(4.4f)
            lineTo(19.2f, 2.6f)
            verticalLineTo(13.9f)
            curveTo(19.2f, 15.4f, 17.8f, 16.5f, 16.1f, 16.5f)
            curveTo(14.4f, 16.5f, 13.0f, 15.4f, 13.0f, 13.9f)
            curveTo(13.0f, 13.85f, 13.0f, 13.8f, 13.0f, 13.75f)
            verticalLineTo(6.9f)
            lineTo(11.1f, 7.3f)
            verticalLineTo(18.1f)
            close()
        }
    }

    val Clock: ImageVector = icon("clock.arrow.circlepath") {
        mono("clock.circle", width = 1.9f) {
            moveTo(12f, 21f)
            curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
            curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
        }
        mono("clock.hands", width = 1.9f) {
            moveTo(12f, 7.4f)
            verticalLineTo(12.2f)
            lineTo(15.6f, 14.2f)
        }
    }

    /** `chart.bar` — 播放统计, monoline three-bar glyph on the same 24 pt / 1.9 pt grid. */
    val ChartBar: ImageVector = icon("chart.bar") {
        mono("chart.bar.one", width = 1.9f) {
            moveTo(5f, 19f)
            verticalLineTo(13f)
        }
        mono("chart.bar.two", width = 1.9f) {
            moveTo(12f, 19f)
            verticalLineTo(8f)
        }
        mono("chart.bar.three", width = 1.9f) {
            moveTo(19f, 19f)
            verticalLineTo(4f)
        }
    }

    val ArrowDownCircle: ImageVector = icon("arrow.down.circle") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2.4f)
            curveTo(17.3f, 2.4f, 21.6f, 6.7f, 21.6f, 12f)
            curveTo(21.6f, 17.3f, 17.3f, 21.6f, 12f, 21.6f)
            curveTo(6.7f, 21.6f, 2.4f, 17.3f, 2.4f, 12f)
            curveTo(2.4f, 6.7f, 6.7f, 2.4f, 12f, 2.4f)
            close()
        }
        mono("arrow.down.circle.glyph", width = 1.9f) {
            moveTo(12f, 7.2f)
            verticalLineTo(16.8f)
            moveTo(8.3f, 13.2f)
            lineTo(12f, 16.9f)
            lineTo(15.7f, 13.2f)
        }
    }

    val ExclamationTriangle: ImageVector = icon("exclamationmark.triangle.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.6f, 3.9f)
            curveTo(11.24f, 2.73f, 12.76f, 2.73f, 13.4f, 3.9f)
            lineTo(21.4f, 18.0f)
            curveTo(22.03f, 19.15f, 21.27f, 20.5f, 20.0f, 20.5f)
            horizontalLineTo(4.0f)
            curveTo(2.73f, 20.5f, 1.97f, 19.15f, 2.6f, 18.0f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 8.6f)
            curveTo(12.58f, 8.6f, 13.05f, 9.07f, 13.05f, 9.65f)
            verticalLineTo(13.6f)
            curveTo(13.05f, 14.18f, 12.58f, 14.65f, 12f, 14.65f)
            curveTo(11.42f, 14.65f, 10.95f, 14.18f, 10.95f, 13.6f)
            verticalLineTo(9.65f)
            curveTo(10.95f, 9.07f, 11.42f, 8.6f, 12f, 8.6f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(13.0f, 17.3f)
            curveTo(13.0f, 17.85f, 12.55f, 18.3f, 12f, 18.3f)
            curveTo(11.45f, 18.3f, 11.0f, 17.85f, 11.0f, 17.3f)
            curveTo(11.0f, 16.75f, 11.45f, 16.3f, 12f, 16.3f)
            curveTo(12.55f, 16.3f, 13.0f, 16.75f, 13.0f, 17.3f)
            close()
        }
    }

    val ArrowClockwise: ImageVector = icon("arrow.clockwise") {
        mono("arrow.clockwise", width = 2.1f) {
            moveTo(19.2f, 12f)
            curveTo(19.2f, 15.98f, 15.98f, 19.2f, 12f, 19.2f)
            curveTo(8.02f, 19.2f, 4.8f, 15.98f, 4.8f, 12f)
            curveTo(4.8f, 8.02f, 8.02f, 4.8f, 12f, 4.8f)
            curveTo(14.5f, 4.8f, 16.7f, 6.08f, 18.0f, 8.02f)
        }
        mono("arrow.clockwise.tip", width = 2.1f) {
            moveTo(18.6f, 3.4f)
            verticalLineTo(8.3f)
            horizontalLineTo(13.7f)
        }
    }

    val Trash: ImageVector = icon("trash") {
        mono("trash.lid", width = 1.9f) {
            moveTo(4.4f, 6.6f)
            horizontalLineTo(19.6f)
        }
        mono("trash.body", width = 1.9f) {
            moveTo(6.3f, 6.6f)
            lineTo(7.15f, 19.4f)
            curveTo(7.21f, 20.28f, 7.94f, 20.95f, 8.82f, 20.95f)
            horizontalLineTo(15.18f)
            curveTo(16.06f, 20.95f, 16.79f, 20.28f, 16.85f, 19.4f)
            lineTo(17.7f, 6.6f)
        }
        mono("trash.handle", width = 1.9f) {
            moveTo(9.6f, 6.4f)
            verticalLineTo(4.6f)
            curveTo(9.6f, 3.99f, 10.09f, 3.5f, 10.7f, 3.5f)
            horizontalLineTo(13.3f)
            curveTo(13.91f, 3.5f, 14.4f, 3.99f, 14.4f, 4.6f)
            verticalLineTo(6.4f)
        }
    }

    val ArrowUpRight: ImageVector = icon("arrow.up.right") {
        mono("arrow.up.right", width = 2.2f) {
            moveTo(7f, 17f)
            lineTo(17f, 7f)
            moveTo(8.6f, 7f)
            horizontalLineTo(17f)
            verticalLineTo(15.4f)
        }
    }

    val WifiSlash: ImageVector = icon("wifi.slash") {
        mono("wifi.slash", width = 1.9f) {
            moveTo(3.2f, 3.2f)
            lineTo(20.8f, 20.8f)
            moveTo(5.0f, 11.2f)
            curveTo(7.0f, 9.5f, 9.4f, 8.6f, 12f, 8.6f)
            curveTo(13.3f, 8.6f, 14.5f, 8.8f, 15.6f, 9.2f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 18.4f)
            curveTo(12.94f, 18.4f, 13.7f, 17.64f, 13.7f, 16.7f)
            curveTo(13.7f, 15.76f, 12.94f, 15.0f, 12f, 15.0f)
            curveTo(11.06f, 15.0f, 10.3f, 15.76f, 10.3f, 16.7f)
            curveTo(10.3f, 17.64f, 11.06f, 18.4f, 12f, 18.4f)
            close()
        }
    }

    val Gearshape: ImageVector = icon("gearshape.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 8.4f)
            curveTo(13.99f, 8.4f, 15.6f, 10.01f, 15.6f, 12f)
            curveTo(15.6f, 13.99f, 13.99f, 15.6f, 12f, 15.6f)
            curveTo(10.01f, 15.6f, 8.4f, 13.99f, 8.4f, 12f)
            curveTo(8.4f, 10.01f, 10.01f, 8.4f, 12f, 8.4f)
            close()
        }
        mono("gearshape.ring", width = 2.0f) {
            moveTo(19.4f, 12f)
            curveTo(19.4f, 11.6f, 19.35f, 11.2f, 19.28f, 10.8f)
            lineTo(21.2f, 9.3f)
            lineTo(19.3f, 6.0f)
            lineTo(17.0f, 6.9f)
            curveTo(16.5f, 6.5f, 15.9f, 6.15f, 15.3f, 5.9f)
            lineTo(15.0f, 3.4f)
            lineTo(11.2f, 3.4f)
            lineTo(10.9f, 5.9f)
            curveTo(10.3f, 6.15f, 9.7f, 6.5f, 9.2f, 6.9f)
            lineTo(6.9f, 6.0f)
            lineTo(5.0f, 9.3f)
            lineTo(6.92f, 10.8f)
            curveTo(6.85f, 11.2f, 6.8f, 11.6f, 6.8f, 12f)
            curveTo(6.8f, 12.4f, 6.85f, 12.8f, 6.92f, 13.2f)
            lineTo(5.0f, 14.7f)
            lineTo(6.9f, 18.0f)
            lineTo(9.2f, 17.1f)
            curveTo(9.7f, 17.5f, 10.3f, 17.85f, 10.9f, 18.1f)
            lineTo(11.2f, 20.6f)
            lineTo(15.0f, 20.6f)
            lineTo(15.3f, 18.1f)
            curveTo(15.9f, 17.85f, 16.5f, 17.5f, 17.0f, 17.1f)
            lineTo(19.3f, 18.0f)
            lineTo(21.2f, 14.7f)
            lineTo(19.28f, 13.2f)
            curveTo(19.35f, 12.8f, 19.4f, 12.4f, 19.4f, 12f)
            close()
        }
    }

    val SpeakerWave: ImageVector = icon("speaker.wave.2.fill") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.2f, 4.6f)
            lineTo(6.6f, 8.5f)
            horizontalLineTo(3.9f)
            curveTo(3.3f, 8.5f, 2.8f, 9.0f, 2.8f, 9.6f)
            verticalLineTo(14.4f)
            curveTo(2.8f, 15.0f, 3.3f, 15.5f, 3.9f, 15.5f)
            horizontalLineTo(6.6f)
            lineTo(11.2f, 19.4f)
            curveTo(11.82f, 19.93f, 12.8f, 19.5f, 12.8f, 18.7f)
            verticalLineTo(5.3f)
            curveTo(12.8f, 4.5f, 11.82f, 4.07f, 11.2f, 4.6f)
            close()
        }
        mono("speaker.wave.2.fill.wave1", width = 1.9f) {
            moveTo(15.6f, 9.4f)
            curveTo(16.6f, 10.4f, 16.6f, 13.6f, 15.6f, 14.6f)
        }
        mono("speaker.wave.2.fill.wave2", width = 1.9f) {
            moveTo(18.4f, 6.9f)
            curveTo(20.4f, 8.9f, 20.4f, 15.1f, 18.4f, 17.1f)
        }
    }

    val ListNumber: ImageVector = icon("text.line.first.and.arrowtriangle.forward") {
        mono("lines", width = 1.9f) {
            moveTo(9.4f, 6.4f)
            horizontalLineTo(20.6f)
            moveTo(9.4f, 12f)
            horizontalLineTo(20.6f)
            moveTo(9.4f, 17.6f)
            horizontalLineTo(20.6f)
        }
        mono("arrow", width = 1.9f) {
            moveTo(3.2f, 6.4f)
            lineTo(3.2f, 17.6f)
            moveTo(3.2f, 12f)
            lineTo(6.2f, 9.4f)
            moveTo(3.2f, 12f)
            lineTo(6.2f, 14.6f)
        }
    }

    val SquareAndArrowUp: ImageVector = icon("square.and.arrow.up") {
        mono("square.and.arrow.up", width = 1.9f) {
            moveTo(12f, 3.4f)
            verticalLineTo(14.2f)
            moveTo(7.9f, 7.3f)
            lineTo(12f, 3.2f)
            lineTo(16.1f, 7.3f)
        }
        mono("square.and.arrow.up.box", width = 1.9f) {
            moveTo(6.0f, 11.0f)
            horizontalLineTo(4.6f)
            curveTo(4.05f, 11.0f, 3.6f, 11.45f, 3.6f, 12.0f)
            verticalLineTo(19.4f)
            curveTo(3.6f, 19.95f, 4.05f, 20.4f, 4.6f, 20.4f)
            horizontalLineTo(19.4f)
            curveTo(19.95f, 20.4f, 20.4f, 19.95f, 20.4f, 19.4f)
            verticalLineTo(12.0f)
            curveTo(20.4f, 11.45f, 19.95f, 11.0f, 19.4f, 11.0f)
            horizontalLineTo(18.0f)
        }
    }

    /** `square.and.arrow.down` — save the cover, distinct from the share glyph beside it. */
    val SquareAndArrowDown: ImageVector = icon("square.and.arrow.down") {
        mono("square.and.arrow.down", width = 1.9f) {
            moveTo(12f, 3.4f)
            verticalLineTo(14.2f)
            moveTo(7.9f, 10.1f)
            lineTo(12f, 14.2f)
            lineTo(16.1f, 10.1f)
        }
        mono("square.and.arrow.down.box", width = 1.9f) {
            moveTo(6.0f, 11.0f)
            horizontalLineTo(4.6f)
            curveTo(4.05f, 11.0f, 3.6f, 11.45f, 3.6f, 12.0f)
            verticalLineTo(19.4f)
            curveTo(3.6f, 19.95f, 4.05f, 20.4f, 4.6f, 20.4f)
            horizontalLineTo(19.4f)
            curveTo(19.95f, 20.4f, 20.4f, 19.95f, 20.4f, 19.4f)
            verticalLineTo(12.0f)
            curveTo(20.4f, 11.45f, 19.95f, 11.0f, 19.4f, 11.0f)
            horizontalLineTo(18.0f)
        }
    }

    /**
     * A filled pencil pointing to the lower-left, in SF's `pencil` idiom.
     *
     * Drawn as one closed outline (shaft plus point) rather than as a stroked line: a stroke would put a
     * round cap on the tip, which reads as a blunt crayon. The tip is separated from the shaft by a small
     * notch line, which is what makes it legible as a pencil rather than as a plain wedge at icon sizes.
     */
    val Pencil: ImageVector = icon("pencil") {
        path(fill = SolidColor(Color.Black)) {
            // Point of the pencil.
            moveTo(4.6f, 19.4f)
            // Lower edge of the shaft, up to the far end.
            lineTo(7.72f, 14.52f)
            lineTo(18.32f, 3.92f)
            // Flat end (the eraser end), one half-width across.
            lineTo(20.08f, 5.68f)
            // Upper edge back down to the point's base.
            lineTo(9.48f, 16.28f)
            close()
        }
        // The graphite/shaft divide. Slightly inset so it reads as a notch, not as a cut through the icon.
        mono("pencil.tip", width = 1.5f) {
            moveTo(7.72f, 14.52f)
            lineTo(9.48f, 16.28f)
        }
    }

    // ------------------------------------------------------------------ browse

    /**
     * SF Symbols' `square.grid.2x2` — four rounded tiles.
     *
     * The 歌单广场 tab: that screen is a grid of playlist covers, so the glyph is the grid itself.
     * Monoline like the other tab glyphs (house, person), on the same 24 pt grid and with the same
     * 2.1 pt rounded stroke, so the four-slot bar still reads as one icon family.
     */
    val SquareGrid2x2: ImageVector = icon("square.grid.2x2") {
        // Literal coordinates, one roundedRect per tile: the bounds tool reads the path data
        // directly, so the geometry must not be hidden behind loop variables.
        mono("square.grid.2x2.tile", width = 1.9f) { roundedRect(3.4f, 3.4f, 7.2f, 2.6f) }
        mono("square.grid.2x2.tile", width = 1.9f) { roundedRect(13.4f, 3.4f, 7.2f, 2.6f) }
        mono("square.grid.2x2.tile", width = 1.9f) { roundedRect(3.4f, 13.4f, 7.2f, 2.6f) }
        mono("square.grid.2x2.tile", width = 1.9f) { roundedRect(13.4f, 13.4f, 7.2f, 2.6f) }
    }
}
