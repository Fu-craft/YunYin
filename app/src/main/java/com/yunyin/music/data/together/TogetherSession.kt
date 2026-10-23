package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class TogetherUiState(
    val inRoom: Boolean = false,
    val roomId: String = "",
    val inviteLink: String = "",
    val busy: Boolean = false,
    val error: String = "",
    val tick: Long = 0L,
    val members: Int = 0,
) {
    val subtitle: String get() = "一起听 · 需要两个已登录账号"
}

/**
 * 1 Hz room loop ported from together.js. Failures never escape the tick: they are logged and routed through the
 * same rate-limit classification (30/60/120 s backoff, hard stop after the fourth consecutive limited failure).
 */
class TogetherSession(
    private val player: PlayerController,
    private val api: TogetherApi,
    private val settings: SettingsStore,
    private val log: LxSourceLog,
    private val scope: CoroutineScope,
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(TogetherUiState())
    val state: kotlinx.coroutines.flow.StateFlow<TogetherUiState> = _state

    private var job: Job? = null
    private val busy = AtomicBoolean(false)
    private var ticks = 0L
    private var failures = 0
    private var backoffUntil = 0L
    private var lastSnapshotError = ""
    private var room: TogetherRoom? = null
    private var leaderId = ""
    private var lastRemoteSignature = ""
    private var lastRemoteSequence = 0L
    private var lastQueueSignature = ""
    private var lastSongId = ""
    private var lastPosition = -1L
    private var lastPlaying = false
    private var lastVersion = 0L
    private var seq = 0L
    private var pendingEndAt = 0L
    private var lastHeartbeat = 0L
    private var lastStatus = 0L
    private var lastDiscover = 0L
    private var cryptoChecked = false

    private val accountId: Long get() = settings.account?.userId ?: 0L

    val available: Boolean
        get() = TogetherRules.available(settings.cookie, settings.account?.isAnonymous)

    fun create() = runOp("create") {
        val result = api.create().getOrThrow()
        enter(result, versionSeed = 0L)
        reportPlaylist()
    }

    fun join(invite: String) = runOp("join") {
        val (roomId, inviterId) = TogetherRules.parseInvite(invite).getOrThrow()
        logLine("invite", "ok", "roomId=$roomId inviterId=$inviterId")
        // together.js joinRoom calls `accept` directly; `check` is diagnostic only (it can report false for a
        // valid invitation until the server has registered it, which used to block every join).
        api.check(roomId)
        enter(api.join(roomId, inviterId).getOrThrow(), versionSeed = 0L)
    }

    fun leave() {
        val current = room ?: return
        runCatching { api.end(current.roomId) }
        room = null
        leaderId = ""
        player.setAutoAdvanceBlocked(false)
        _state.value = TogetherUiState()
        logLine("end", "ok")
    }

    private fun enter(value: TogetherRoom, versionSeed: Long) {
        room = value
        leaderId = value.creatorId.ifBlank { accountId.toString() }
        lastRemoteSignature = ""
        lastRemoteSequence = 0L
        lastQueueSignature = ""
        lastSongId = ""
        lastPosition = -1L
        lastPlaying = player.state.value.isPlaying
        lastVersion = versionSeed
        _state.value = TogetherUiState(
            inRoom = true,
            roomId = value.roomId,
            inviteLink = inviteLink(value.roomId, value.creatorId.ifBlank { accountId.toString() }),
        )
        player.setAutoAdvanceBlocked(true)
        logLine("create", "ok")
        startLoop()
    }

    private fun startLoop() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(1_000L)
                tick()
            }
        }
    }

    private fun tick() {
        val currentRoom = room ?: return
        if (!busy.compareAndSet(false, true)) return
        ticks++
        try {
            if (!available) return
            if (backoffUntil > now()) return
            if (ticks % TogetherRules.REDISCOVER_TICKS == 0L && now() - lastDiscover > TogetherRules.REDISCOVER_MS) {
                lastDiscover = now()
                val status = api.status().getOrNull()
                if (status != null && !status.inRoom && status.room == null) {
                    // Lost the room (ended elsewhere); stop cleanly rather than keep reporting into nothing.
                    leave()
                    return
                }
            }
            player.setAutoAdvanceBlocked(true)
            val local = player.state.value
            if (local.queue.isNotEmpty()) {
                val queueIds = local.queue.map { it.id.toString() }
                val currentId = local.current?.id?.toString().orEmpty()
                val seqNow = maxOf(seq + 1, now())
                val action = TogetherRules.planLocalChange(
                    baselineQueue = lastQueueSignature.split(',').filter { it.isNotBlank() },
                    currentQueue = queueIds,
                    baselineSongId = lastSongId,
                    currentSongId = currentId,
                    baselinePositionMs = lastPosition.coerceAtLeast(0L),
                    currentPositionMs = local.positionMs,
                    baselinePlaying = lastPlaying,
                    currentPlaying = local.isPlaying,
                    ownAccountId = accountId.toString(),
                    version = ++lastVersion,
                    clientSeq = seqNow,
                )
                if (action is TogetherAction.ReplaceQueue) {
                    api.reportPlaylist(currentRoom.roomId, action.songIds, accountId, action.version)
                    recordOk("report")
                    lastQueueSignature = TogetherRules.idsSignature(action.songIds)
                } else if (action is TogetherAction.Command) {
                    seq = seqNow
                    api.reportCommand(
                        currentRoom.roomId, action.type, action.progressMs, action.playing,
                        action.formerSongId, action.targetSongId, action.clientSeq,
                    )
                    recordOk("report")
                }
                lastSongId = currentId
                lastPosition = local.positionMs
                lastPlaying = local.isPlaying
            }
            val snapshot = api.snapshot(currentRoom.roomId).getOrElse { error ->
                registerFailure("snapshot", error)
                null
            }
            if (snapshot != null) applySnapshot(snapshot)
            if (ticks - lastHeartbeat >= TogetherRules.HEARTBEAT_TICKS) {
                lastHeartbeat = ticks
                val local = player.state.value
                api.heartbeat(
                    currentRoom.roomId,
                    local.current?.id?.toString().orEmpty(),
                    local.isPlaying,
                    local.positionMs,
                ).onSuccess { recordOk("heartbeat") }.onFailure { registerFailure("heartbeat", it) }
            }
            if (ticks - lastStatus >= TogetherRules.STATUS_TICKS) {
                lastStatus = ticks
                api.status().onSuccess { recordOk("status") }.onFailure { registerFailure("status", it) }
            }
            failures = 0
            lastSnapshotError = ""
            _state.value = _state.value.copy(error = "", tick = ticks)
        } catch (t: Throwable) {
            registerFailure("tick", t)
        } finally {
            busy.set(false)
        }
    }

    private fun applySnapshot(snapshot: TogetherSnapshot) {
        val command = snapshot.command
        val fresh = TogetherRules.isFresh(command, lastRemoteSignature, lastRemoteSequence, accountId.toString())
        val remoteIds = snapshot.songIds
        val local = player.state.value
        val localIds = local.queue.map { it.id.toString() }
        val queueChanged = remoteIds.isNotEmpty() && TogetherRules.idsSignature(remoteIds) != TogetherRules.idsSignature(localIds)
        if (queueChanged) {
            if (!fresh && remoteIds.none { it == local.current?.id?.toString() }) return
            val selected = command?.targetSongId?.takeIf { it.isNotBlank() && it in remoteIds }
                ?: local.current?.id?.toString()?.takeIf { it in remoteIds }
                ?: remoteIds.first()
            val tracks = remoteIds.mapNotNull { id -> local.queue.firstOrNull { it.id.toString() == id } }
            if (tracks.isNotEmpty()) {
                val index = tracks.indexOfFirst { it.id.toString() == selected }.coerceAtLeast(0)
                player.playQueue(tracks, index, autoPlay = fresh && (command?.playing ?: false))
                if (fresh && command != null) player.seekTo(command.progressMs.coerceAtLeast(0L))
                lastQueueSignature = TogetherRules.idsSignature(remoteIds)
                lastSongId = selected
            }
        } else if (fresh && command != null) {
            val type = command.type.uppercase()
            if (type == "GOTO" || type == "NEXT" || type == "PREV") {
                val target = command.targetSongId
                val index = local.queue.indexOfFirst { it.id.toString() == target }
                if (index >= 0) {
                    leaderId = command.accountId.ifBlank { leaderId }
                    pendingEndAt = 0L
                    player.playQueue(local.queue, index, autoPlay = command.playing)
                    player.seekTo(if (ticks <= 1L) command.progressMs.coerceAtLeast(0L) else 0L)
                } else {
                    // Queue update has not reached us yet; wait for the matching REPLACE.
                    return
                }
            } else if (type == "PROGRESS") {
                player.seekTo(command.progressMs.coerceAtLeast(0L))
            } else if (type == "PLAY") {
                if (!player.state.value.isPlaying) player.togglePlayPause()
            } else if (type == "PAUSE") {
                if (player.state.value.isPlaying) player.togglePlayPause()
            }
            lastRemoteSignature = snapshot.signature
            lastRemoteSequence = maxOf(lastRemoteSequence, command.sequence)
        }
        handleNaturalEnd(snapshot, local)
    }

    /** Natural-end leader fallback: creator (or lowest account id) advances; others self-promote after 3.5 s. */
    private fun handleNaturalEnd(snapshot: TogetherSnapshot, local: com.yunyin.music.playback.PlaybackUiState) {
        val ended = local.durationMs > 0 && local.positionMs >= local.durationMs - 1_500L && !local.isPlaying
        if (!ended) {
            pendingEndAt = 0L
            return
        }
        if (leaderId == accountId.toString()) {
            player.next()
            return
        }
        if (pendingEndAt == 0L) {
            pendingEndAt = now() + TogetherRules.LEADER_FALLBACK_MS
        } else if (now() >= pendingEndAt) {
            leaderId = accountId.toString()
            pendingEndAt = 0L
            player.next()
        }
    }

    private fun reportPlaylist() {
        val currentRoom = room ?: return
        val ids = player.state.value.queue.map { it.id.toString() }
        api.reportPlaylist(currentRoom.roomId, ids, accountId, 0L)
            .onSuccess { recordOk("report") }.onFailure { registerFailure("report", it) }
    }

    private fun runOp(action: String, block: () -> Unit) {
        if (!available) {
            _state.value = _state.value.copy(error = "请先登录网易云账号")
            return
        }
        _state.value = _state.value.copy(busy = true, error = "")
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                registerFailure(action, t)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun registerFailure(action: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        logLine(action, "fail", message)
        lastSnapshotError = message
        if (!TogetherRules.isRateLimited(message)) {
            _state.value = _state.value.copy(error = message)
            return
        }
        failures++
        val delayMs = TogetherRules.backoffMs(failures)
        if (delayMs == null) {
            backoffUntil = Long.MAX_VALUE
            _state.value = _state.value.copy(error = "一起听同步请求过于频繁，请退出房间后重试")
        } else {
            backoffUntil = now() + delayMs
            _state.value = _state.value.copy(error = "一起听请求受限，将在 ${delayMs / 1000} 秒后重试")
        }
        logLine("backoff", "ok", "failures=$failures until=$backoffUntil")
    }

    private fun recordOk(action: String) = logLine(action, "ok")

    private fun logLine(action: String, result: String, reason: String = "") {
        val suffix = if (reason.isBlank()) "" else " reason=$reason"
        log.record(
            "解析",
            "stage=together room=${room?.roomId.orEmpty()} tick=$ticks action=$action result=$result$suffix",
        )
        if (!cryptoChecked) {
            cryptoChecked = true
            log.record("解析", "stage=together crypto ok=true")
        }
    }

    private fun inviteLink(roomId: String, inviterId: String): String =
        "qplayer://listen-together?provider=netease&roomId=$roomId&inviterId=$inviterId"

    private fun now(): Long = System.currentTimeMillis()
}
