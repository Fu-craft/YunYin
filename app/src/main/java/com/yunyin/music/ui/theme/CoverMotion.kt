// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cover's pause/resume settle, as **user-tunable** parameters.
 *
 * The shape of this motion is a feel judgement — the paused cover either lands too hard or wobbles
 * depending on the device, the cover art and the user's taste — so instead of freezing one pair of
 * spring constants it is exposed through 「封面动效手感」 and persisted. Defaults are the softer pair
 * (low stiffness, a small settle on pause) that reads like Apple Music rather than like a snap.
 *
 * The defaults and their clamps live here, and both the player and the tuning preview build their
 * animation specs from the same functions, so what the preview shows is exactly what the player does.
 */
object CoverMotion {

    /** Prior values live in `SettingsStore`; unknown/absurd stored values are clamped on read. */
    data class Params(
        val pauseDamping: Float = DEFAULT_PAUSE_DAMPING,
        val pauseStiffness: Float = DEFAULT_PAUSE_STIFFNESS,
        val resumeDamping: Float = DEFAULT_RESUME_DAMPING,
        val resumeStiffness: Float = DEFAULT_RESUME_STIFFNESS,
        val pausedScale: Float = DEFAULT_PAUSED_SCALE,
        /** Visible rebound on play, as a fraction of the spring's overshoot. */
        val resumePop: Float = DEFAULT_RESUME_POP,
    ) {
        /** The values this instance represents after clamping; used to normalise stored settings. */
        fun sanitized(): Params = Params(
            pauseDamping = pauseDamping.coerceIn(MIN_DAMPING, MAX_DAMPING),
            pauseStiffness = pauseStiffness.coerceIn(MIN_STIFFNESS, MAX_STIFFNESS),
            resumeDamping = resumeDamping.coerceIn(MIN_DAMPING, MAX_DAMPING),
            resumeStiffness = resumeStiffness.coerceIn(MIN_STIFFNESS, MAX_STIFFNESS),
            pausedScale = pausedScale.coerceIn(MIN_PAUSED_SCALE, MAX_PAUSED_SCALE),
            resumePop = resumePop.coerceIn(MIN_RESUME_POP, MAX_RESUME_POP),
        )
    }

    const val MIN_DAMPING = 0.40f
    const val MAX_DAMPING = 1.00f
    const val MIN_STIFFNESS = 100f
    const val MAX_STIFFNESS = 1500f
    const val MIN_PAUSED_SCALE = 0.80f
    const val MAX_PAUSED_SCALE = 1.00f
    const val MIN_RESUME_POP = 0.05f
    const val MAX_RESUME_POP = 0.50f

    /**
     * Apple Music's pause: **smooth, no bounce, and slow**.
     *
     * The artwork sinks steadily and lands without a wobble — critically damped (1.0) — but the
     * stiffness is low (220) so the whole move takes roughly 0.55–0.65 s and reads as weight rather
     * than a snap. High stiffness with critical damping is what made an earlier build feel 生硬.
     */
    const val DEFAULT_PAUSE_DAMPING = 1.00f
    const val DEFAULT_PAUSE_STIFFNESS = 220f

    /**
     * Resume: a visible elastic pop. Damping below critical (0.45) is what lets the cover shoot past
     * its full size and settle back — the "press play and the artwork springs back" feel — while 420
     * keeps it quick enough that the first beat is not delayed by the animation.
     */
    const val DEFAULT_RESUME_DAMPING = 0.60f
    const val DEFAULT_RESUME_STIFFNESS = 400f

    /**
     * The spring's overshoot is a fraction of the *travel*, and the travel is only 6 % (0.94 → 1.0),
     * so even a strongly under-damped spring popped past full size by well under one percent — which
     * is why the rebound was invisible. This gain scales the visible overshoot directly: at the
     * default 0.25 a ~20 % spring overshoot shows as a ~5 % pop.
     */
    const val DEFAULT_RESUME_POP = 0.20f

    /** How far the cover recedes while paused. Apple's own step is subtle, not a shrink. */
    const val DEFAULT_PAUSED_SCALE = 0.94f

    private val _params = MutableStateFlow(Params())
    val params: StateFlow<Params> = _params.asStateFlow()

    /** Called once the persisted settings are known, and again whenever the tuner changes them. */
    fun set(params: Params) {
        _params.value = params.sanitized()
    }

    fun pauseSpec(params: Params): FiniteAnimationSpec<Float> =
        spring(dampingRatio = params.pauseDamping, stiffness = params.pauseStiffness)

    fun resumeSpec(params: Params): FiniteAnimationSpec<Float> =
        spring(dampingRatio = params.resumeDamping, stiffness = params.resumeStiffness)
}
