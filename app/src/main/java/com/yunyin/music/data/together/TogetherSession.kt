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
    /**
     * How long this transport's room codes are.
     *
     * Travels with the state because the *UI* has to validate input, and the answer differs per
     * transport (six for the self-hosted relay, twelve where the code is also a topic name). Assuming
     * one length made a six-character code permanently invalid on the other transport, so the join
     * button could never be pressed.
     */
    val codeLength: Int = 12,
    /**
     * Whether joining is done by typing a code.
     *
     * False for the official room, whose id is a 43-character server string that nobody types: the
     * invite is the *(roomId, inviterId)* pair, so the sheet offers share/paste instead of a text field.
     * A field for a value no one can enter is worse than no field — it invites the attempt and then
     * fails.
     */
    val joinsByTyping: Boolean = true,
    /**
     * The text to hand to the peer.
     *
     * Carried in the state rather than read from the transport so the sheet stays a pure function of
     * the state it is given, and so the difference between the transports (a bare code, or a two-part
     * invite) lives in one place.
     */
    val invite: String? = null,
    /**
     * True while a create/join is in flight.
     *
     * Needed because connecting can take a while — every candidate endpoint gets a turn — and without
     * it the sheet simply sat there, which reads as "the button does nothing". (That is what prompted
     * repeated taps, which used to stack up and crash.)
     */
    val connecting: Boolean = false,
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

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TogetherUiState> = _state.asStateFlow()

    /** This device's current playback, or null when there is nothing to publish. */
    var localPlayback: () -> LocalPlayback? = { null }

    /** Applies a correction to the player. The host decides how (and may refuse). */
    var applyAction: (SyncAction) -> Unit = {}

    /**
     * The queue this device is playing from, supplied by the host.
     *
     * Published once when a room starts. It is not needed for the current-song sync, but a room whose
     * playlist has never been set is a *different* room as far as the server is concerned, and the
     * `sync/playlist/get` read path is the one place a command could go missing because of it. Sending what
     * we already have costs one request and removes that whole class of doubt.
     */
    var localQueue: () -> List<Long> = { emptyList() }

    private var loop: Job? = null
    private var lastAppliedAt = 0L

    /**
     * The identity of the last peer command this device acted on — see [TogetherSync.peerIdentity].
     *
     * This is the whole of the room's ordering: a command with a different identity is something the peer
     * *did* since we last looked, and is followed; the same identity is a state we have already seen. No
     * counter, and therefore nothing that can drift out of step with the server's own numbering — which is
     * what made every command after the first one look "older" and get ignored.
     */
    private var lastPeerIdentity: String? = null

    /**
     * A discrete action the user announced and the loop has not published yet.
     *
     * Held rather than published at the call site so that a burst — a scrub sends many seeks — collapses
     * into one command carrying the *latest* position, which is the only one that matters.
     */
    private var pendingAction: TogetherLocalAction? = null

    /**
     * What was last published, so a change nobody announced is still noticed.
     *
     * Playback can move on its own (a track ends and the next starts), and those transitions must reach
     * the peer just as a tap does. Comparing against this is what catches them.
     */
    private var lastPublishedSongId = 0L
    private var lastPublishedPlaying = false

    /** The queue the host last published, so it is sent once per queue rather than every tick. */
    private var lastPublishedQueue: List<Long> = emptyList()

    /**
     * Wakes the loop early, so a local action is published at once instead of up to a poll interval
     * later. `CONFLATED` because several rapid actions need only one prompt re-publish — the payload
     * always carries the *current* state, so nothing is lost by coalescing them.
     */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * Records that the user did something to playback (next, previous, play/pause, a seek).
     *
     * Every local action **must** call this: it is what queues the announcement and wakes the loop so the
     * peer sees the change immediately rather than after the next scheduled poll.
     *
     * [action] names what the user did, when the call site knows. The official transport needs it: its
     * protocol distinguishes "a different song" from "a seek" from "a pause", and only an explicit
     * command changes the room's state. The hand-rolled transports ignore it and re-publish their whole
     * state, so passing it there is harmless.
     */
    fun noteUserAction(action: TogetherLocalAction? = null) {
        if (action != null) pendingAction = action
        // Tell the loop to publish now: a peer should see the new track immediately, not after the
        // next scheduled poll.
        wakeups.trySend(Unit)
    }

    /** Sleeps for a poll interval, or until [noteUserAction] asks for an immediate publish. */
    private suspend fun awaitNextPoll() {
        withTimeoutOrNull(transport.pollIntervalMs) { wakeups.receive() }
    }

    /**
     * Forgets which peer command was last acted on, for a room that is only just starting.
     *
     * It must not carry over between rooms: an identity left from a previous conversation could match the
     * first command in a new room and suppress the very adoption this is meant to make once — which is the
     * reported "joining a room never adopts the other member's song".
     */
    private fun resetSync() {
        lastPeerIdentity = null
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
        resetSync()
        launchWork {
            when (val result = transport.createRoom(uid, name)) {
                is TogetherResult.Ok -> {
                    _state.value = _state.value.copy(
                        code = result.value, isHost = true, ended = null, error = null,
                        server = transport.serverLabel,
                        invite = transport.inviteText(result.value),
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
        // Normalised by the *transport*, not here: uppercase is right for a typed alphanumeric code and
        // wrong for the official invite, whose id is lowercase hex with a separator in it.
        val key = transport.normaliseJoinInput(code)
        if (key.isBlank() || uid.isBlank()) return
        resetSync()
        launchWork {
            // Look the room up first so a wrong code says so instead of joining nothing. The relay
            // still validates on join; this is only for a better message.
            when (val info = transport.roomInfo(key)) {
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
            when (val joined = transport.join(key, uid, name)) {
                is TogetherResult.Ok -> {
                    _state.value = _state.value.copy(
                        code = key, isHost = false, ended = null, error = null,
                        server = transport.serverLabel,
                        invite = transport.inviteText(key),
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
        _state.value = initialState()
        launchWork {
            if (wasHost) transport.closeRoom(code) else transport.leave(code, uid)
        }
    }

    /** A state with nothing in it but the transport's own facts — its endpoint and code format. */
    private fun initialState() = TogetherUiState(
        configured = transport.configured,
        server = transport.serverLabel,
        codeLength = transport.codeLength,
        joinsByTyping = transport.joinsByTyping,
    )

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

    /**
     * Starts the room's tick loop.
     *
     * ## The order inside a tick is the whole design
     *
     * This is a faithful port of the reference implementation's tick, and the order is what makes two
     * devices converge:
     *
     * ```
     * 1. read what this device is playing
     * 2. announce any *local* change                       (report before reading the room)
     * 3. heartbeat, then read the room
     * 4. apply what the room says
     * 5. re-baseline from the state *after* applying       (so the adopted state is not announced back)
     * ```
     *
     * An earlier version did 3-4 and then 2 — announcing **after** applying, using the playback captured
     * *before* the apply. So a member that had just adopted the host's track immediately announced the track
     * it was in the middle of abandoning, overwriting the room's command with its own old song; the host then
     * followed that, and the two sat on each other's track. Reported as "切歌不跟随" and "成员切不了歌".
     *
     * ## Why the baseline starts at the *current* state, not at zero
     *
     * Step 5 only prevents re-announcing the state we just adopted if the baseline is what "already
     * published" means. Starting it at zero would make a joiner's own (pre-join) track look like an
     * unannounced change on the first tick, which is the same overwrite one tick earlier. So the baseline
     * begins as "whatever is playing right now" on both sides, and the **host** additionally publishes its
     * song and queue when the room is created — which is exactly the asymmetry the reference has (its
     * `createRoom` reports, its `joinRoom` only adopts).
     */
    private fun startLoop(uid: String, name: String) {
        stopLoop()
        lastAppliedAt = 0L
        resetSync()
        pendingAction = null

        // The baseline is the state as it stands, so nothing looks like a change until it actually changes.
        val current = localPlayback()
        lastPublishedSongId = current?.songId ?: 0L
        lastPublishedPlaying = current?.playing ?: false
        val currentQueue = localQueue()
        lastPublishedQueue = currentQueue
        TogetherLog.add("ROOM start uid=$uid name=$name host=${_state.value.isHost}")

        loop = scope.launch {
            // The host sets the room up: the queue first, then the command — the reference's order, because
            // a `GOTO` refers to a playlist and a command sent into a room with no list has nothing to point
            // at.
            if (_state.value.isHost) {
                if (currentQueue.isNotEmpty()) {
                    transport.publishQueue(currentQueue, uid, System.currentTimeMillis())
                }
                if (current != null) {
                    transport.publishAction(
                        action = TogetherLocalAction.Goto,
                        songId = current.songId,
                        positionMs = current.positionMs,
                        playing = current.playing,
                        clientSeq = 0L,
                    )
                }
            }

            while (isActive) {
                val code = _state.value.code ?: break
                // 1-2. What is playing now, and announce it if it changed since the last tick.
                val before = localPlayback()?.takeIf { it.songId > 0L }
                if (before != null) publishLocalAction(before)

                // 3. Heartbeat (throttled inside the transport) and read the room.
                val result = if (before != null) {
                    transport.postState(
                        code = code,
                        uid = uid,
                        name = name,
                        songId = before.songId,
                        positionMs = before.positionMs,
                        playing = before.playing,
                        seq = 0L,
                    )
                } else {
                    transport.pollPeers(code, uid)
                }

                when (result) {
                    is TogetherResult.Ok -> {
                        val peers = result.value
                        _state.value = _state.value.copy(peers = peers, error = null)
                        // 4. Apply the room's state.
                        apply(peers, before)
                        // 5. Re-baseline from what is playing *now*, i.e. after the apply. This is the line
                        //    that stops the adopted song being sent straight back to its author.
                        localPlayback()?.let { after ->
                            if (after.songId > 0L) {
                                lastPublishedSongId = after.songId
                                lastPublishedPlaying = after.playing
                            }
                        }
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

    /**
     * Publishes a discrete action, if there is one to publish.
     *
     * Two sources, and the difference between them is what keeps a room from oscillating:
     *
     *  - **An announced action** ([pendingAction]) — the user tapped something. Always published, even
     *    right after a correction, because it is a deliberate act that must reach the peer.
     *  - **An unannounced change** — a different song or play state appeared between ticks, which is
     *    what an automatic track advance looks like. Published too, *except* inside the window after
     *    applying the peer's own correction: that change is the correction arriving, and republishing it
     *    would send it straight back, so the two devices would trade it forever.
     *
     * The window is keyed off [lastAppliedAt] rather than a new timer so it is the same [TogetherSync]
     * settle interval the drift correction uses — one concept, one knob.
     */
    private suspend fun publishLocalAction(mine: LocalPlayback) {
        val announced = pendingAction
        pendingAction = null
        val action = announced ?: when {
            mine.songId != lastPublishedSongId -> TogetherLocalAction.Goto
            mine.playing != lastPublishedPlaying ->
                if (mine.playing) TogetherLocalAction.Play else TogetherLocalAction.Pause
            else -> null
        }
        // The baseline moves whether or not anything was published — this is the reference's `baseline()`
        // at the end of its report step, and it is what makes "changed since last time" mean what it says.
        //
        // There is deliberately no "suppress while settling" here any more: a state adopted from the room is
        // already in the baseline (the loop re-baselines after applying), so it cannot present itself as a
        // local change in the first place. An extra window on top of that only risked swallowing a real
        // change — a track that auto-advanced just after a correction, for instance.
        lastPublishedSongId = mine.songId
        lastPublishedPlaying = mine.playing
        if (action == null) return
        transport.publishAction(
            action = action,
            songId = mine.songId,
            positionMs = mine.positionMs,
            playing = mine.playing,
            clientSeq = 0L,
        )
    }

    /**
     * Publishes the queue to the room, once per distinct queue.
     *
     * Best-effort and deliberately outside the loop: it is a convenience (a joiner sees the list you are
     * playing from instead of only the current song), and a failure must not disturb the room's actual
     * job, which is keeping the current track aligned.
     */
    fun publishQueue(queue: List<Long>, uid: String, version: Long) {
        if (queue.isEmpty() || queue == lastPublishedQueue) return
        lastPublishedQueue = queue
        scope.launch {
            runCatching { transport.publishQueue(queue, uid, version) }
        }
    }

    /**
     * Applies whatever the room says, given this device's own playback (which may be nothing).
     *
     * ## What decides "the peer acted"
     *
     * Not a counter — the identity of the command ([TogetherSync.peerIdentity]: author, song and play
     * state). A different identity means they did something since we last looked, and it is followed at
     * once; the same identity means the room is reporting the state we have already seen, so only the
     * drift is nudged, and only outside the settle window.
     *
     * ## Why `mine == null` is *not* an early return
     *
     * It used to be, and that was a bug with a very visible symptom: **joining a room while nothing was
     * playing did nothing at all.** The loop deliberately polls even with no local playback (see
     * [startLoop]), so the peer's song was being read and then thrown away by this guard — which is why a
     * freshly opened app could sit in a room showing the host as present and never start their music
     * ("房间里只有我", "没有切换到起风了").
     *
     * With nothing playing there is nothing to preserve, so the peer's track is adopted outright. It is
     * applied **once per peer command** ([lastPeerIdentity]) rather than on every tick: the player loads a
     * track asynchronously, so `localPlayback()` stays null for a few polls after the request, and
     * re-issuing it would restart the song repeatedly until it took.
     */
    private fun apply(peers: List<TogetherPeerState>, mine: LocalPlayback?) {
        // The most recently updated peer is the one to follow when the room has more than two members.
        val peer = peers.filter { it.hasSong }.maxByOrNull { it.updatedAt }
        if (peer == null) {
            // Logged only when there *was* a peer to consider, so an idle room is not noisy — but a room
            // where the other member is present and on a song is exactly the case that must not pass
            // silently.
            if (peers.isNotEmpty()) TogetherLog.add("APPLY nothing: peers present but none has a song")
            return
        }

        // The command is followed when it differs from the state we have already settled on. Both
        // `lastPeerIdentity` (what we acted on) and `lastPublished…` (what our own state is) count, because
        // adopting the room's state sets both.
        val identity = TogetherSync.peerIdentity(peer)
        val isNew = identity != lastPeerIdentity

        if (mine == null) {
            if (!isNew) return
            lastPeerIdentity = identity
            lastAppliedAt = System.currentTimeMillis()
            TogetherLog.add("APPLY adopt song=${peer.songId} pos=${peer.positionMs} (nothing playing locally)")
            applyAction(SyncAction.PlaySong(peer.songId, peer.positionMs))
            lastPublishedSongId = peer.songId
            lastPublishedPlaying = peer.playing
            return
        }

        val action = TogetherSync.decide(
            local = mine,
            peer = peer,
            peerIsNew = isNew,
            lastAppliedAt = lastAppliedAt,
            now = System.currentTimeMillis(),
        )
        if (action == SyncAction.None) {
            // The interesting negative: the peer IS on a song and we still did nothing — either this is the
            // state we already have, or the difference is inside the drift tolerance.
            TogetherLog.add(
                "APPLY none: local(song=${mine.songId} pos=${mine.positionMs} play=${mine.playing}) " +
                    "vs peer(song=${peer.songId} pos=${peer.positionMs} play=${peer.playing}) new=$isNew",
            )
            return
        }
        lastAppliedAt = System.currentTimeMillis()
        lastPeerIdentity = identity
        TogetherLog.add("APPLY $action (local song=${mine.songId} pos=${mine.positionMs} play=${mine.playing})")
        applyAction(action)
        // The room's state is now ours, so there is nothing new to announce: marking it as published stops
        // the settle window expiring later and sending the adopted song straight back to its author.
        lastPublishedSongId = peer.songId
        lastPublishedPlaying = peer.playing
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    /**
     * Runs a create/join/leave in the session's scope, with the previous one cancelled and **no
     * exception allowed out**.
     *
     * Two failures are being fixed here, and both showed up as "tapping join does nothing, then the app
     * crashes if you tap a few times":
     *
     *  - **Nothing may escape.** The scope is `Dispatchers.Main.immediate` on the session's own
     *    SupervisorJob, so an exception thrown by an unhandled coroutine on the main thread is not
     *    swallowed — it takes the process down. Network code must not have that power, so every failure
     *    becomes a message in [TogetherUiState.error].
     *  - **Only one at a time.** Each tap used to start an independent job, and a second concurrent join
     *    raced the first: the transport's "already connected" shortcut could not see a connection that
     *    was still being established, so it opened another and leaked the first. Cancelling the previous
     *    job makes repeated taps harmless.
     */
    private fun launchWork(block: suspend () -> Unit) {
        workJob?.cancel()
        _state.value = _state.value.copy(connecting = true, error = null)
        workJob = scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Includes OOM-style Errors? No — `Throwable` is deliberate: a network stack can throw
                // anything, and none of it should be allowed to kill the app. Cancellation is rethrown
                // above so structured concurrency still works.
                setError(e.message ?: "一起听连接失败")
            } finally {
                _state.value = _state.value.copy(connecting = false)
            }
        }
    }

    private var workJob: Job? = null

    private fun setError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}
