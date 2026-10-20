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
     * The cover settling back while playback pauses.
     *
     * Critically damped, not [SpringSettle]'s 0.8: on an element this large an overshoot reads as a
     * flick at the end of the move, and the paused cover must not cross its rest value (the shared
     * element's monotonicity rule — see `sharedCoverPausedScale`). Stiffness 240 is deliberate
     * weight: the cover recedes under its own mass instead of twitching to 92 %.
     */
    val CoverPauseSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 240f)

    /**
     * The cover coming back when playback resumes: the same critically damped shape, but crisper.
     *
     * Pause is the user's considered action, so it may take its time; resume is the system answering
     * the user, and the music has already started — the cover catching up should not be the slow part.
     */
    val CoverResumeSettle: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 480f)

    /** Button / press feedback: 100-160ms. */
    const val PressDuration = 160

    /** Small popovers, chips, glyph and label swaps: 125-200ms. */
    const val SmallDuration = 200

    /** Sheets and drawers: modals are allowed 200-500ms. */
    const val SheetEnterDuration = 320
    const val SheetExitDuration = 260

    /** Content cross-fades. */
    const val CrossfadeDuration = 220
}
