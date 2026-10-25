// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Shared motion tokens.
 *
 * Every curve and duration here is copied from the audit's rule catalog, not invented. The point of
 * the file is that the app has one ease-out, not five that almost match — see `AppleShapes` in
 * [Theme.kt] for the same idea applied to corner radii.
 *
 * Rules of thumb the values encode:
 *  - entering or exiting -> [EaseOut] (starts fast, feels responsive)
 *  - moving / morphing on screen -> [EaseInOut]
 *  - sheets and drawers -> [EaseDrawer] (the iOS drawer curve)
 *  - springs follow Apple's `duration 0.5 / bounce 0.2`: dampingRatio 0.8 is a subtle overshoot.
 */
object Motion {
    /** cubic-bezier(0.23, 1, 0.32, 1) — strong ease-out, for anything entering or leaving. */
    val EaseOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** cubic-bezier(0.77, 0, 0.175, 1) — strong ease-in-out, for on-screen movement. */
    val EaseInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** cubic-bezier(0.32, 0.72, 0, 1) — the iOS-like drawer curve for sheets. */
    val EaseDrawer: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * The Compose form of Apple's `{ type: "spring", duration: 0.5, bounce: 0.2 }`.
     * Settles in roughly half a second with a small overshoot; use for release/settle motion.
     */
    val SpringSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 400f)

    /**
     * The cover receding while playback pauses: **critically damped, and quick**.
     *
     * The asymmetry is the point. Pausing is the user's own action, so the cover should *land*
     * immediately — no wobble, no second motion to wait for. Damping 1 with stiffness 1300 settles
     * in ~150–180 ms, which is the same shape NeriPlayer's Clean interaction uses for its press-in
     * phase (`spring(dampingRatio = 1f, stiffness = 1400f)`).
     */
    val CoverPauseSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1300f)

    /**
     * The cover coming back when playback resumes: **under-damped, so it springs past full size**
     * and settles back — the "Q弹" the pause deliberately does not have.
     *
     * Damping 0.62 / stiffness 420 is NeriPlayer's Clean release phase
     * (`spring(dampingRatio = 0.62f, stiffness = 420f)`), which is tuned for exactly this: a short
     * overshoot that reads as elastic rather than as a wobble. Roughly 400 ms in total.
     */
    val CoverResumeSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 420f)

    /**
     * Button / press feedback: 100-160ms.
     *
     * Deliberately **not** slowed with the rest of the app: a press must still feel instant, and a
     * slow acknowledgement reads as lag rather than calm.
     */
    const val PressDuration = 160

    /** Small popovers, chips, glyph and label swaps: the calmer 3.41.4 tempo (was 200). */
    const val SmallDuration = 260

    /** Content cross-fades: phase changes, lyric documents, titles (was 220). */
    const val CrossfadeDuration = 280

    /**
     * Tab switches. Faster than a content cross-fade on purpose: switching tabs is a high-frequency
     * action, so the fade is only there to keep the swap from flashing — 160ms reads as instant with
     * a legible change, while the regular cross-fade at 280ms feels sluggish on a tab bar.
     */
    const val TabCrossfadeDuration = 160

    /** Scrims and simple overlay fades (was 200 in sheets, 320 on the cover overlay). */
    const val ScrimDuration = 360

    /** List-item appearance when paging appends rows (was 260). */
    const val ItemAppearDuration = 320

    /** Sheets and drawers: modals are allowed 200-500ms (was 320 / 260). */
    const val SheetEnterDuration = 420
    const val SheetExitDuration = 340

    /**
     * Pushed subpages (资料库 subpages, 本地音乐, 下载中心): the same 420/340 tempo as sheets because a
     * full-screen page is a modal-scale surface. Enter is ease-out so the page arrives fast and settles;
     * exit is shorter because the user has already decided to leave.
     */
    const val PageEnterDuration = 420
    const val PageExitDuration = 340

    /** Outgoing page parallax spec (kept as a token; applied where a shared host can scale the old page). */
    const val PageParallaxScale = 0.98f
    const val PageScrimAlpha = 0.06f
}
