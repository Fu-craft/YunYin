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
    ) {
        /** The values this instance represents after clamping; used to normalise stored settings. */
        fun sanitized(): Params = Params(
            pauseDamping = pauseDamping.coerceIn(MIN_DAMPING, MAX_DAMPING),
            pauseStiffness = pauseStiffness.coerceIn(MIN_STIFFNESS, MAX_STIFFNESS),
            resumeDamping = resumeDamping.coerceIn(MIN_DAMPING, MAX_DAMPING),
            resumeStiffness = resumeStiffness.coerceIn(MIN_STIFFNESS, MAX_STIFFNESS),
            pausedScale = pausedScale.coerceIn(MIN_PAUSED_SCALE, MAX_PAUSED_SCALE),
        )
    }

    const val MIN_DAMPING = 0.40f
    const val MAX_DAMPING = 1.00f
    const val MIN_STIFFNESS = 100f
    const val MAX_STIFFNESS = 1500f
    const val MIN_PAUSED_SCALE = 0.80f
    const val MAX_PAUSED_SCALE = 1.00f

    /** Soft landing with a hint of settle; stiffness low enough that it reads as mass, not a snap. */
    const val DEFAULT_PAUSE_DAMPING = 0.85f
    const val DEFAULT_PAUSE_STIFFNESS = 380f

    /** Slightly more overshoot on the way back, so resuming feels elastic. */
    const val DEFAULT_RESUME_DAMPING = 0.68f
    const val DEFAULT_RESUME_STIFFNESS = 380f

    /** How far the cover recedes while paused. */
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
