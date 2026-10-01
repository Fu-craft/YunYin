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
        localSeq: Long = 0L,
        driftToleranceMs: Long = DRIFT_TOLERANCE_MS,
        settleMs: Long = SETTLE_MS,
    ): SyncAction {
        // Nobody to follow.
        if (peer == null || !peer.hasSong) return SyncAction.None

        // Whose intent is newer? This is the rule that makes the room usable at all, and the one that
        // was missing: without it any local action (next/previous/play) was immediately undone,
        // because the peer's *stale* state still disagreed and read as "we are out of sync". The user
        // saw it as "切歌点了没反应".
        //
        // Ordering is by a Lamport clock, not by time. The two devices' clocks differ by tens of
        // seconds (measured), so comparing timestamps would routinely pick the wrong winner; a counter
        // that each side advances past whatever it has seen orders actions causally instead.
        if (localSeq > peer.seq) return SyncAction.None
        if (peer.seq > localSeq) {
            // The peer acted more recently, so its state is the truth — including deliberately, not
            // subject to the settle window below.
            return followPeer(local, peer, driftToleranceMs)
        }

        // Same sequence number: neither side has acted since the last exchange, so this is the
        // continuous drift correction. Here the settle window applies, because the two sides are
        // nudging each other rather than one of them having asked for something.
        if (lastAppliedAt != 0L && now - lastAppliedAt < settleMs) return SyncAction.None
        return followPeer(local, peer, driftToleranceMs)
    }

    /** What it takes to match [peer]: adopt its track, seek to it, or match its play state. */
    private fun followPeer(
        local: LocalPlayback,
        peer: TogetherPeerState,
        driftToleranceMs: Long,
    ): SyncAction {
        // A different track wins outright and carries the position with it.
        if (peer.songId != local.songId) {
            return SyncAction.PlaySong(peer.songId, peer.positionMs)
        }
        // Same track: correct a real drift, since a seek also settles the play state.
        if (kotlin.math.abs(peer.positionMs - local.positionMs) > driftToleranceMs) {
            return SyncAction.Seek(peer.songId, peer.positionMs)
        }
        // Position agrees; only the play/pause state can be wrong.
        if (peer.playing != local.playing) {
            return if (peer.playing) SyncAction.Play else SyncAction.Pause
        }
        return SyncAction.None
    }

    /**
     * The counter to put on the next report after seeing [peerSeq].
     *
     * `max(local, peer) + 1` is the Lamport rule: the new value is greater than anything either side
     * has used, so "larger seq" always means "causally later" no matter whose clock is right.
     */
    fun nextSeq(localSeq: Long, peerSeq: Long): Long =
        maxOf(localSeq, peerSeq) + 1L
}
