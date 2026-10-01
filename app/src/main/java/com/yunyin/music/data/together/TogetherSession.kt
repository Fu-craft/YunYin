package com.yunyin.music.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
) {
    val inRoom: Boolean get() = code != null && ended == null
}

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
    private var seq = 0L

    /**
     * Bumped by [noteUserAction]. The room's tie-break uses it so that when both members act at once
     * the newer intent wins instead of both corrections landing.
     */
    fun noteUserAction() {
        seq++
    }

    fun createRoom(uid: String, name: String) {
        if (uid.isBlank()) return
        launchWork {
            when (val result = transport.createRoom(uid, name)) {
                is TogetherResult.Ok -> {
                    _state.value = _state.value.copy(
                        code = result.value, isHost = true, ended = null, error = null,
                    )
                    startLoop(uid, name)
                }
                is TogetherResult.NoRoom -> setError(result.message)
                is TogetherResult.Failed -> setError(result.message)
            }
        }
    }

    fun joinRoom(code: String, uid: String, name: String) {
        val trimmed = code.trim().uppercase()
        if (trimmed.isBlank() || uid.isBlank()) return
        launchWork {
            // Look the room up first so a wrong code says so instead of joining nothing. The relay
            // still validates on join; this is only for a better message.
            when (val info = transport.roomInfo(trimmed)) {
                is TogetherResult.NoRoom -> {
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
                    )
                    startLoop(uid, name)
                }
                is TogetherResult.NoRoom -> setError(joined.message)
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

    fun dismissEnded() {
        _state.value = TogetherUiState(configured = transport.configured)
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
                        _state.value = _state.value.copy(peers = peers, error = null)
                        apply(peers, mine)
                    }
                    // A room that vanished is terminal: say so and stop rather than retrying forever.
                    is TogetherResult.NoRoom -> {
                        _state.value = _state.value.copy(ended = result.message)
                        break
                    }
                    // Everything else is transient (a dropped poll); keep the loop and try again.
                    is TogetherResult.Failed -> _state.value = _state.value.copy(error = result.message)
                }
                delay(POLL_MS)
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
        )
        if (action == SyncAction.None) return
        lastAppliedAt = System.currentTimeMillis()
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

    private companion object {
        /**
         * Two seconds: long enough that the relay and the network are not hammered, short enough that
         * a correction lands about as fast as a lyric line. Start/stop and track changes are also
         * published immediately by the host, so the visible latency is usually lower than this.
         */
        const val POLL_MS = 2_000L
    }
}
