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

    /**
     * Load [songId] and put it at [positionMs].
     *
     * [playing] carries the peer's own play state, because selecting a track and starting it are two
     * decisions: a `GOTO` sent while the sender was paused must land paused, or the receiver would begin
     * playing something nobody asked it to play.
     */
    data class PlaySong(
        val songId: Long,
        val positionMs: Long,
        val playing: Boolean = true,
    ) : SyncAction

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
 * ## How "whose action is newer" is decided — and why not with counters
 *
 * The room holds **one command**, and the read path reports it along with **who authored it**. That is
 * enough to order events without any clock at all:
 *
 *  - a command authored by *us* is never even handed to this rule (the transport drops it as an echo), so
 *    our own local actions can never be undone by our own stale state;
 *  - a command authored by the peer is a **thing they did** — so whenever it differs from the last one we
 *    saw, it is followed immediately, whatever we are playing.
 *
 * What "differs" means is [peerIdentity]: the author, the song, the play state and the *kind* of command,
 * which change exactly when someone acts and stay stable while they merely listen.
 *
 * ## What the rule acts on, and why the kind matters
 *
 * Where the transport names the command (`GOTO`/`NEXT`/`PREV`/`PROGRESS`/`PLAY`/`PAUSE` — the official
 * protocol does), the rule acts on that name directly. Where it only reports a state (the hand-rolled
 * transports), the rule compares the track, then the play state, then the position.
 *
 * Getting this wrong is not cosmetic: inferring the action from a position comparison makes a mismatch read
 * as "seek", and a seek never starts or stops the music. The reported symptom was precisely that — pause on
 * one device and the other kept playing, play and the other stayed paused, and a next/previous did nothing
 * on the receiving side.
 *
 * An earlier version compared a **local Lamport counter** against the peer's server sequence instead. That
 * was wrong in a way that only shows up against a real server: the two values come from different clocks
 * (ours a counter, the server's its own numbering), so once the local counter had been advanced past the
 * peer's value — which adopting the peer's first command does — every later command from the peer compared
 * as *older* and was ignored. The symptom was exact: **the first song syncs and nothing after it ever
 * does.** Dropping the counter removes the whole class of error, and matches the reference implementation,
 * which also has no local sequence.
 *
 * ## Anti-ping-pong
 *
 * Two devices correcting each other is the failure mode of every "listen together" implementation: A seeks,
 * B sees a drift and seeks back, and the song stutters forever. The rule below makes that impossible by
 * construction: **only a new peer command is applied at all**, and a repeated one is left alone — no
 * branch here ever re-asserts a correction the peer did not just ask for.
 *
 * @param driftToleranceMs how far apart the two positions may be before it is worth correcting. A second or
 *   two of skew is inaudible as "out of sync" but constant micro-seeks are very audible, so this is
 *   deliberately not zero.
 * @param settleMs how long after applying a peer's state we stop reacting to it.
 */
object TogetherSync {

    const val DRIFT_TOLERANCE_MS = 2_000L
    const val SETTLE_MS = 6_000L

    /**
     * What identifies a peer command.
     *
     * Author, song, play state and *kind*: the things that change when the other person does something.
     * Position is deliberately excluded — it advances on its own, so including it would make every poll look
     * like a new command and turn the drift correction into a seek loop. The kind is included because a
     * `PLAY` and a `PROGRESS` on the same track at the same position are different acts (the first starts
     * the music, the second only moves the needle) and must not collapse into one identity.
     */
    fun peerIdentity(peer: TogetherPeerState): String =
        "${peer.uid}|${peer.songId}|${peer.playing}|${peer.commandType.uppercase()}"

    /**
     * @param local what this device is playing right now
     * @param peer the other member's command, or null while nobody has reported in
     * @param peerIsNew true when this command differs from the last one we acted on — i.e. the peer has done
     *   something since, rather than this being the same state re-reported
     * @param lastAppliedAt the wall clock at which the last peer correction was applied
     * @param now the wall clock
     */
    fun decide(
        local: LocalPlayback,
        peer: TogetherPeerState?,
        peerIsNew: Boolean,
        lastAppliedAt: Long,
        now: Long,
        driftToleranceMs: Long = DRIFT_TOLERANCE_MS,
        settleMs: Long = SETTLE_MS,
    ): SyncAction {
        // Nobody to follow.
        if (peer == null || !peer.hasSong) return SyncAction.None
        // Nothing has changed since we last acted on this command: leave the player alone.
        if (!peerIsNew) return SyncAction.None

        // The peer acted: follow it.
        //
        // **Only a new command is applied — never a periodic re-assertion of the same one.** That is the
        // reference implementation's rule, and dropping it is what made the two devices fight: with a drift
        // branch here, "they paused, I seek back to playing" and "I play, they pull me back to paused" chase
        // each other forever, because each correction looks like a difference worth correcting. The
        // reference has no such branch; the two devices stay aligned because they both follow the same
        // commands, not because they keep nudging each other.
        return followPeer(local, peer, driftToleranceMs)
    }

    /** What it takes to match [peer]: adopt its track, seek to it, or match its play state. */
    private fun followPeer(
        local: LocalPlayback,
        peer: TogetherPeerState,
        driftToleranceMs: Long,
    ): SyncAction {
        // When the transport says *what* the peer did, act on that and nothing else.
        //
        // This is the correction for the reported inversion. Inferring the action from a position
        // comparison made a mismatch read as "seek", and a seek only moves the needle — it never starts or
        // stops the music. So a pause arrived, the receiver seeked but kept playing ("我暂停他还在播"),
        // and a play arrived and the receiver seeked but stayed paused ("我播放他停着"). Acting on the
        // command's own kind removes the ambiguity: a start command starts, a stop command stops, and only
        // a `PROGRESS` touches the position.
        //
        // How long the position has advanced is folded in by the transport ([playbackAdvance]) so the seek
        // lands where the sender's playback actually is, rather than where it was when the command was
        // issued.
        val kind = peer.commandType.uppercase()
        if (kind.isNotEmpty()) {
            return when (kind) {
                // A track change: load the song and start it, as the reference's `playback.select` does.
                // Same song means the peer only restarted it — start, do not reload.
                "GOTO", "NEXT", "PREV" ->
                    if (peer.songId != local.songId) {
                        SyncAction.PlaySong(peer.songId, peer.positionMs, peer.playing)
                    } else if (!local.playing && peer.playing) {
                        SyncAction.Play
                    } else {
                        SyncAction.None
                    }
                // A scrub: move the position only. The play state is whatever the peer's own command said,
                // so this one must not be turned into a play/pause.
                "PROGRESS" -> if (kotlin.math.abs(peer.positionMs - local.positionMs) > driftToleranceMs) {
                    SyncAction.Seek(peer.songId, peer.positionMs)
                } else {
                    SyncAction.None
                }
                "PLAY" -> if (!local.playing) SyncAction.Play else SyncAction.None
                "PAUSE" -> if (local.playing) SyncAction.Pause else SyncAction.None
                // A kind we do not know: fall through to the comparison rather than guessing.
                else -> legacyFollow(local, peer, driftToleranceMs)
            }
        }
        return legacyFollow(local, peer, driftToleranceMs)
    }

    /**
     * The rule for a transport that reports a bare state rather than a named action.
     *
     * The play state is decided **before** the position: a mismatch in the play state is an act (someone
     * pressed a button), while a mismatch in position can be nothing but drift. Testing drift first would
     * hand back a seek and leave the play state wrong — the same confusion the official path above exists
     * to avoid.
     */
    private fun legacyFollow(
        local: LocalPlayback,
        peer: TogetherPeerState,
        driftToleranceMs: Long,
    ): SyncAction {
        // A different track wins outright and carries the position with it.
        if (peer.songId != local.songId) {
            return SyncAction.PlaySong(peer.songId, peer.positionMs, peer.playing)
        }
        // Same track: the play state is the deliberate part, so it is settled first.
        if (peer.playing != local.playing) {
            return if (peer.playing) SyncAction.Play else SyncAction.Pause
        }
        // Position agrees on play/pause; only then is a real drift worth a seek.
        if (kotlin.math.abs(peer.positionMs - local.positionMs) > driftToleranceMs) {
            return SyncAction.Seek(peer.songId, peer.positionMs)
        }
        return SyncAction.None
    }
}
