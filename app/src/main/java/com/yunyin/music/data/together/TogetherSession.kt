package com.yunyin.music.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A room as the UI sees it. */
data class TogetherUiState(
    val configured: Boolean = false,
    val code: String? = null,
    val isHost: Boolean = false,
    val members: List<TogetherMember> = emptyList(),
    val peers: List<TogetherPeerState> = emptyList(),
    /** Set when the room is gone (host closed it, or the code expired) so the UI can explain. */
    val ended: String? = null,
    val error: String? = null,
    /**
     * The endpoint in use, shown in the sheet.
     *
     * A transport is a rendezvous, so two members on different endpoints sit in two rooms that both
     * look empty — showing the address makes that mismatch visible instead of mysterious.
     */
    val server: String? = null,
) {
    val inRoom: Boolean get() = code != null && ended == null
}

/**
 * A copy with only the transient notices cleared.
 *
 * A pure function because the distinction it encodes is the one that broke: closing the panel clears
 * a *notice*, while leaving a room is an explicit, separate act. Conflating the two silently ended
 * sessions, so it is pinned by a test rather than left to a call site to remember.
 */
internal fun TogetherUiState.noticesCleared(): TogetherUiState =
    copy(ended = null, error = null)

/**
 * Runs a listen-together room: publishes this device's playback and applies the peer's.
 *
 * ## Where the corrections come from
 *
 * The relay is polled, not pushed, so the loop does three things per tick in one round trip:
 * post this device's state (a heartbeat that also keeps this member alive on the relay) and read
 * the peers back. The decision of *what to change* is [TogetherSync]'s, deliberately separated so
 * the anti-ping-pong rule can be tested without a player.
 *
 * ## Why the callbacks rather than a PlayerController reference
 *
 * Reading playback and applying a correction are supplied by the host. That keeps this class free of
 * the playback and UI layers — it can be driven by a fake in a test, and the dependency arrow stays
 * one-way (`ui -> data`), the same direction the rest of the app uses.
 */
class TogetherSession(
    private val transport: TogetherTransport,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(TogetherUiState(configured = transport.configured))
    val state: StateFlow<TogetherUiState> = _state.asStateFlow()

    /** This device's current playback, or null when there is nothing to publish. */
    var localPlayback: () -> LocalPlayback? = { null }

    /** Applies a correction to the player. The host decides how (and may refuse). */
    var applyAction: (SyncAction) -> Unit = {}

    private var loop: Job? = null
    private var lastAppliedAt = 0L

    /**
     * This device's logical clock, advanced on every local action **and** past every peer value seen.
     *
     * This is what decides who wins when the two members disagree, and it must not be a wall clock:
     * the devices' clocks differ by tens of seconds, so "later timestamp" would be wrong about half the
     * time. A Lamport counter orders actions causally instead — see [TogetherSync.nextSeq].
     */
    private var seq = 0L

    /** The highest counter seen from a peer, so a local action can be made to exceed it. */
    private var peerSeqSeen = 0L

    /**
     * Wakes the loop early, so a local action is published at once instead of up to a poll interval
     * later. `CONFLATED` because several rapid actions need only one prompt re-publish — the payload
     * always carries the *current* state, so nothing is lost by coalescing them.
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * Records that the user did something to playback (next, previous, play/pause, a seek).
     *
     * Every local action **must** call this. Without it the peer's previous state still disagrees with
     * ours, reads as "out of sync", and the track is pulled back within a poll or two — the reported
     * "加入房间以后无法切歌". The bump is what makes the room yield to whoever acted last, and it also
     * gives the action immediate priority over the continuous drift correction.
     */
    fun noteUserAction() {
        seq = TogetherSync.nextSeq(seq, peerSeqSeen)
        // Tell the loop to publish now: a peer should see the new track immediately, not after the
        // next scheduled poll.
        wakeups.trySend(Unit)
    }

    /** Sleeps for a poll interval, or until [noteUserAction] asks for an immediate publish. */
    private suspend fun awaitNextPoll() {
        withTimeoutOrNull(transport.pollIntervalMs) { wakeups.receive() }
    }

    /**
     * Starts a room's logical clock from zero.
     *
     * The clock orders "who acted last" **within one room**, so it must not carry over between rooms:
     * this session object lives for the whole app, and a counter left over from earlier rooms made a
     * fresh peer look stale by comparison — its first report is `seq = 1` while ours could be anything.
     * The symptom was "joining a room never adopts the other member's song", which looks like a sync
     * failure and is really a units error: comparing counters from two different conversations.
     */
    private fun resetClock() {
        seq = 0L
        peerSeqSeen = 0L
    }

    private companion object {
        /**
         * How long to wait after being rate limited.
         *
         * Long on purpose: the allowance refills over a period, so retrying at the normal cadence
         * spends requests that are certain to be refused and extends the outage. A minute is long
         * enough for a typical refill and short enough that the room recovers on its own.
         */
        const val RATE_LIMIT_BACKOFF_MS = 60_000L
    }

    fun createRoom(uid: String, name: String) {
        if (uid.isBlank()) return
        resetClock()
        launchWork {
            when (val result = transport.createRoom(uid, name)) {
                is TogetherResult.Ok -> {
                    _state.value = _state.value.copy(
                        code = result.value, isHost = true, ended = null, error = null,
                        server = transport.serverLabel,
                    )
                    startLoop(uid, name)
                }
                is TogetherResult.NoRoom -> setError(result.message)
                is TogetherResult.RateLimited -> setError(result.message)
                is TogetherResult.Failed -> setError(result.message)
            }
        }
    }

    fun joinRoom(code: String, uid: String, name: String) {
        val trimmed = code.trim().uppercase()
        if (trimmed.isBlank() || uid.isBlank()) return
        resetClock()
        launchWork {
            // Look the room up first so a wrong code says so instead of joining nothing. The relay
            // still validates on join; this is only for a better message.
            when (val info = transport.roomInfo(trimmed)) {
                is TogetherResult.NoRoom -> {
                    setError(info.message)
                    return@launchWork
                }
                is TogetherResult.RateLimited -> {
                    setError(info.message)
                    return@launchWork
                }
                is TogetherResult.Failed -> {
                    setError(info.message)
                    return@launchWork
                }
                is TogetherResult.Ok -> Unit
            }
            when (val joined = transport.join(trimmed, uid, name)) {
                is TogetherResult.Ok -> {
                    _state.value = _state.value.copy(
                        code = trimmed, isHost = false, ended = null, error = null,
                        server = transport.serverLabel,
                    )
                    startLoop(uid, name)
                }
                is TogetherResult.NoRoom -> setError(joined.message)
                is TogetherResult.RateLimited -> setError(joined.message)
                is TogetherResult.Failed -> setError(joined.message)
            }
        }
    }

    fun leaveRoom(uid: String) {
        val code = _state.value.code ?: return
        stopLoop()
        val wasHost = _state.value.isHost
        _state.value = TogetherUiState(configured = transport.configured)
        launchWork {
            if (wasHost) transport.closeRoom(code) else transport.leave(code, uid)
        }
    }

    /**
     * Clears a transient notice (an ended room, a failed join) **without touching the room**.
     *
     * Called when the panel closes. It used to rebuild the whole state, which quietly discarded the
     * room code — so reopening the panel offered "create a room" again while the polling loop had
     * already stopped on the now-null code, and the peer was never told anyone left. Closing a panel
     * must never end a session.
     */
    fun dismissEnded() {
        _state.value = _state.value.noticesCleared()
    }

    /** Starts the loop against an already-known room (used when a room survives a reconnect). */
    fun resume(uid: String, name: String) {
        if (_state.value.inRoom) startLoop(uid, name)
    }

    private fun startLoop(uid: String, name: String) {
        stopLoop()
        lastAppliedAt = 0L
        loop = scope.launch {
            while (isActive) {
                val code = _state.value.code ?: break
                val mine = localPlayback()
                val result = if (mine != null && mine.songId > 0L) {
                    transport.postState(
                        code = code,
                        uid = uid,
                        name = name,
                        songId = mine.songId,
                        positionMs = mine.positionMs,
                        playing = mine.playing,
                        seq = seq,
                    )
                } else {
                    transport.pollPeers(code, uid)
                }
                when (result) {
                    is TogetherResult.Ok -> {
                        val peers = result.value
                        // Observe the peer's clock before deciding, so our next local action can win
                        // against it (Lamport: the new value must exceed everything we have seen).
                        peers.maxOfOrNull { it.seq }?.let { peerSeqSeen = maxOf(peerSeqSeen, it) }
                        _state.value = _state.value.copy(peers = peers, error = null)
                        apply(peers, mine)
                        awaitNextPoll()
                    }
                    // A room that vanished is terminal: say so and stop rather than retrying forever.
                    is TogetherResult.NoRoom -> {
                        _state.value = _state.value.copy(ended = result.message)
                        break
                    }
                    // Rate limited: say so plainly (it is actionable), and back off hard. Continuing at
                    // the normal cadence would keep spending an allowance that is already exhausted —
                    // the limit refills over time, so the only thing that helps is waiting longer.
                    is TogetherResult.RateLimited -> {
                        _state.value = _state.value.copy(error = result.message)
                        delay(RATE_LIMIT_BACKOFF_MS)
                    }
                    // Everything else is transient as well (a dropped exchange); keep the loop going.
                    is TogetherResult.Failed -> {
                        _state.value = _state.value.copy(error = result.message)
                        awaitNextPoll()
                    }
                }
            }
        }
    }

    private fun apply(peers: List<TogetherPeerState>, mine: LocalPlayback?) {
        if (mine == null) return
        // The most recently updated peer is the one to follow when the room has more than two members.
        val peer = peers.filter { it.hasSong }.maxByOrNull { it.updatedAt } ?: return
        val action = TogetherSync.decide(
            local = mine,
            peer = peer,
            lastAppliedAt = lastAppliedAt,
            now = System.currentTimeMillis(),
            localSeq = seq,
        )
        if (action == SyncAction.None) return
        lastAppliedAt = System.currentTimeMillis()
        // Adopting the peer's state *is* an action, so our clock moves past theirs. Without this the
        // next comparison would still see us as the stale side and we would re-apply the same
        // correction on every poll.
        seq = TogetherSync.nextSeq(seq, peer.seq)
        applyAction(action)
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    private fun launchWork(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
