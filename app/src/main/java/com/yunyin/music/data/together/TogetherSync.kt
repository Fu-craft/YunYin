package com.yunyin.music.data.together

/**
 * What the player should do to match a peer.
 *
 * A closed set rather than a set of booleans so the call sites cannot forget a case, and so the
 * decision is testable without a player.
 */
sealed interface SyncAction {
    /** Nothing to do. */
    data object None : SyncAction

    /** Load [songId] and start it at [positionMs]; used when the peer is on a different track. */
    data class PlaySong(val songId: Long, val positionMs: Long) : SyncAction

    /** Same track, but the positions have drifted past the tolerance. */
    data class Seek(val songId: Long, val positionMs: Long) : SyncAction

    data object Play : SyncAction

    data object Pause : SyncAction
}

/** A snapshot of what this device is playing, in the same terms as [TogetherPeerState]. */
data class LocalPlayback(
    val songId: Long,
    val positionMs: Long,
    val playing: Boolean,
)

/**
 * The room's sync rule, as a pure function.
 *
 * ## Why this is its own unit
 *
 * Two devices correcting each other is the failure mode of every "listen together" implementation:
 * A seeks, B sees a drift and seeks back, A sees a drift again, and the song stutters forever. The
 * rule below makes that impossible by construction rather than by tuning — every correction is
 * attributed to the peer, and a correction is **suppressed while one is still settling**, so the
 * two cannot chase each other.
 *
 * @param driftToleranceMs how far apart the two positions may be before it is worth correcting. A
 *   second or two of skew is inaudible as "out of sync" but constant micro-seeks are very audible,
 *   so this is deliberately not zero.
 * @param settleMs how long after applying a peer's state we stop reacting to it.
 */
object TogetherSync {

    const val DRIFT_TOLERANCE_MS = 2_000L
    const val SETTLE_MS = 6_000L

    /**
     * @param local what this device is playing right now
     * @param peer the other member's state, or null while nobody has reported in
     * @param lastAppliedAt the wall clock at which the last peer correction was applied
     * @param now the wall clock
     */
    fun decide(
        local: LocalPlayback,
        peer: TogetherPeerState?,
        lastAppliedAt: Long,
        now: Long,
        driftToleranceMs: Long = DRIFT_TOLERANCE_MS,
        settleMs: Long = SETTLE_MS,
    ): SyncAction {
        // Nobody to follow.
        if (peer == null || !peer.hasSong) return SyncAction.None

        // Still settling from the last correction: reacting again now would be the ping-pong.
        if (lastAppliedAt != 0L && now - lastAppliedAt < settleMs) return SyncAction.None

        // A different track always wins, and resets the clock: the peer is the source of the song.
        if (peer.songId != local.songId) {
            return SyncAction.PlaySong(peer.songId, peer.positionMs)
        }

        // Same track: correct a real drift first, since a seek also settles the play state.
        val drift = peer.positionMs - local.positionMs
        if (kotlin.math.abs(drift) > driftToleranceMs) {
            return SyncAction.Seek(peer.songId, peer.positionMs)
        }

        // Position agrees; only the play/pause state can be wrong.
        if (peer.playing != local.playing) {
            return if (peer.playing) SyncAction.Play else SyncAction.Pause
        }
        return SyncAction.None
    }

    /**
     * How long to wait before the *peer's* next report is treated as a fresh intent.
     *
     * When both members act at almost the same moment, the one with the higher [TogetherPeerState.seq]
     * wins and the other yields. Without a tie-break the two corrections arrive together and the
     * earlier one is undone — visible as the song jumping twice.
     */
    fun peerHasPriority(peer: TogetherPeerState, localSeq: Long): Boolean = peer.seq > localSeq
}
