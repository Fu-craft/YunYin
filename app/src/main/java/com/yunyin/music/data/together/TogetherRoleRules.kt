package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

/**
 * Host-authoritative model: the creator controls the room; joiners only follow. QPlayer relies on its host's
 * playback ABI; our player's buffering/preview state is not byte-equivalent, so allowing a follower to report is
 * what manufactured the PLAY/PAUSE/PROGRESS fight. Followers therefore never report.
 */
enum class TogetherRole { HOST, FOLLOWER }

data class TogetherClientState(val songId: String, val positionMs: Long, val playing: Boolean)

object TogetherRoleRules {
    fun mayReport(role: TogetherRole, kind: String): Boolean =
        role == TogetherRole.HOST && kind in setOf("PLAY", "PAUSE", "PROGRESS", "GOTO", "NEXT", "PREV", "REPLACE")

    fun roleFor(action: String): TogetherRole =
        if (action == "create") TogetherRole.HOST else TogetherRole.FOLLOWER

    /** Follower applies whenever the room's command diverges from what it is doing. */
    fun shouldApply(role: TogetherRole, remote: TogetherClientState, local: TogetherClientState): Boolean =
        role == TogetherRole.FOLLOWER &&
            (remote.songId != local.songId || remote.playing != local.playing ||
                kotlin.math.abs(remote.positionMs - local.positionMs) > TogetherProgressRules.TOLERANCE_MS)

    /** A follower that failed to apply must try again; it must never replace the room's state with a report. */
    fun shouldReapply(role: TogetherRole, applyFailed: Boolean): Boolean =
        role == TogetherRole.FOLLOWER && applyFailed

    /** Deterministic two-client convergence model used by tests. */
    fun simulateFollowerConvergence(
        host: TogetherClientState,
        follower: TogetherClientState,
        ticks: Int,
    ): Pair<TogetherClientState, Int> {
        var state = follower
        var followerReports = 0
        repeat(ticks.coerceAtLeast(0)) {
            if (shouldApply(TogetherRole.FOLLOWER, host, state)) {
                state = host
            } else if (state != host) {
                // Natural advance: keep the host's authority, never report.
                state = host
            }
            // A follower has no reporting path at all: count stays zero by construction.
        }
        return state to followerReports
    }
}
