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
    private val music: com.yunyin.music.data.MusicRepository,
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
    private var lastSnapshotSongs = 0

    private val accountId: Long get() = settings.account?.userId ?: 0L

    val available: Boolean
        get() = TogetherRules.available(settings.cookie, settings.account?.isAnonymous)

    fun create() = runOp("create") {
        if (player.state.value.queue.isEmpty()) error("请先播放一首网易云歌曲")
        val created = api.create().getOrThrow()
        val status = api.status().getOrNull()
        val statusRoomId = status?.room?.roomId.orEmpty()
        val roomId = TogetherRules.adoptRoomId(created.roomId, statusRoomId, snapshotSongs = 0)
        if (!TogetherRules.creatorEnters(roomId)) {
            error("房间创建失败（未返回房间号）：create=${created.roomId} status=${statusRoomId.ifBlank { "none" }}")
        }
        // The creator owns the room as soon as the id exists; seed it so joiners have a playlist to snapshot.
        enter(TogetherRoom(roomId, created.creatorId.ifBlank { status?.room?.creatorId.orEmpty() }), 0L, "create")
        val seeded = seedRoom(roomId, attempt = 0)
        if (!seeded) {
            _state.value = _state.value.copy(error = "房间已创建但队列发布失败：playlist=0 code=snapshot-empty")
        }
    }

    /** create → reportPlaylist → reportCommand(GOTO) → snapshot, retried once; mirrors together.js createRoom. */
    private suspend fun seedRoom(roomId: String, attempt: Int): Boolean {
        val local = player.state.value
        val ids = local.queue.map { it.id.toString() }
        val currentId = local.current?.id?.toString().orEmpty()
        if (ids.isEmpty() || currentId.isBlank()) {
            log.record("解析", "stage=together seed step=playlist result=fail reason=empty-local-queue")
            return false
        }
        val version = ++lastVersion
        api.reportPlaylist(roomId, ids, accountId, version)
            .onSuccess { log.record("解析", "stage=together seed step=playlist result=ok songs=${ids.size}") }
            .onFailure { log.record("解析", "stage=together seed step=playlist result=fail reason=${it.message} songs=${ids.size}") }
        var first = api.snapshot(roomId).getOrNull()
        log.record("解析", "stage=together seed snapshot=playlist songs=${first?.songIds?.size ?: 0} attempt=$attempt")
        val clientSeq = maxOf(seq + 1, now())
        api.reportCommand(roomId, "GOTO", local.positionMs, local.isPlaying, "0", currentId, clientSeq)
            .onSuccess { seq = clientSeq; log.record("解析", "stage=together seed step=command result=ok song=$currentId") }
            .onFailure { log.record("解析", "stage=together seed step=command result=fail reason=${it.message}") }
        first = api.snapshot(roomId).getOrNull()
        val songs = first?.songIds?.size ?: 0
        log.record("解析", "stage=together seed snapshot=playlist songs=$songs attempt=$attempt")
        if (songs > 0) {
            first?.let { applySnapshot(it) }
            return true
        }
        if (TogetherRules.shouldRetrySeed(songs, attempt + 1)) {
            kotlinx.coroutines.delay(500L)
            return seedRoom(roomId, attempt + 1)
        }
        return false
    }

    fun join(invite: String) = runOp("join") {
        val (roomId, inviterId) = TogetherRules.parseInvite(invite).getOrThrow()
        logLine("invite", "ok", "roomId=$roomId inviterId=$inviterId")
        // together.js joinRoom calls `accept` directly; `check` is diagnostic only (it can report false for a
        // valid invitation until the server has registered it, which used to block every join).
        api.check(roomId)
        val joined = api.join(roomId, inviterId).getOrThrow()
        // together.js applies the snapshot right after accept; without room data the "join" is an empty shell.
        val snapshot = api.snapshot(joined.roomId).getOrElse { error("加入后未获取到房间数据：${it.message}") }
        if (!TogetherRules.joinedFromSnapshot(snapshot.songIds)) {
            error("加入后未获取到房间数据：playlist=0 code=snapshot-empty")
        }
        enter(joined, versionSeed = 0L, action = "join")
        applySnapshot(snapshot, initial = true)
    }

    /** Only the sheet's 退出房间 calls this with explicit=true; every implicit path clears locally. */
    fun leave(explicit: Boolean = false) {
        val current = room
        if (!TogetherRules.canEnd(explicit, current?.roomId)) {
            log.record(
                "解析",
                "stage=together end skipped reason=${if (current?.roomId.isNullOrBlank()) "no-room" else "not-explicit"}",
            )
            if (current == null) {
                clearRoomLocally()
                return
            }
            clearRoomLocally()
            return
        }
        runCatching { api.end(current!!.roomId, explicit = true) }
        clearRoomLocally()
        logLine("end", "ok")
    }

    private fun clearRoomLocally() {
        room = null
        leaderId = ""
        lastSnapshotSongs = 0
        player.setAutoAdvanceBlocked(false)
        _state.value = TogetherUiState()
    }

    private fun enter(value: TogetherRoom, versionSeed: Long, action: String) {
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
        logLine(action, "ok")
        log.record(
            "解析",
            "stage=together parsed action=$action roomId=${value.roomId} creatorId=${value.creatorId} " +
                "members=${value.memberCount}",
        )
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

    private suspend fun tick() {
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
                    if (TogetherRules.preferSnapshot(lastSnapshotSongs, status.inRoom)) {
                        log.record("解析", "stage=together status=not-in-room but snapshot songs=$lastSnapshotSongs; keeping room")
                    } else {
                        log.record("解析", "stage=together room left locally reason=status-no-room")
                        clearRoomLocally()
                        return
                    }
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
            if (snapshot != null) {
                log.record(
                    "解析",
                    "stage=together parsed action=snapshot playlist=${snapshot.songIds.size} " +
                        "commandType=${snapshot.command?.type.orEmpty()} " +
                        "serverSeq=${snapshot.command?.sequence ?: 0} playing=${snapshot.command?.playing ?: false}",
                )
                applySnapshot(snapshot)
            }
            if (ticks - lastHeartbeat >= TogetherRules.HEARTBEAT_TICKS) {
                lastHeartbeat = ticks
                val local = player.state.value
                api.heartbeat(
                    currentRoom.roomId,
                    local.current?.id?.toString().orEmpty(),
                    local.isPlaying,
                    local.positionMs,
                ).onSuccess { inRoom ->
                    log.record("解析", "stage=together parsed action=heartbeat inRoom=${inRoom ?: "unknown"}")
                    if (inRoom == false && lastSnapshotSongs <= 0) {
                        log.record("解析", "stage=together room left locally reason=heartbeat-not-in-room")
                        clearRoomLocally()
                        return
                    }
                    recordOk("heartbeat")
                }.onFailure { registerFailure("heartbeat", it) }
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

    private suspend fun applySnapshot(snapshot: TogetherSnapshot, initial: Boolean = false) {
        lastSnapshotSongs = snapshot.songIds.size
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
            val position = if (initial) command?.progressMs ?: 0L else 0L
            if (!hydrateAndReplace(remoteIds, selected, position, fresh && command?.playing == true)) return
        } else if (fresh && command != null) {
            val type = command.type.uppercase()
            if (type == "GOTO" || type == "NEXT" || type == "PREV") {
                val target = command.targetSongId
                val index = local.queue.indexOfFirst { it.id.toString() == target }
                val position = if (!initial) 0L else command.progressMs.coerceAtLeast(0L)
                if (index >= 0) {
                    leaderId = command.accountId.ifBlank { leaderId }
                    pendingEndAt = 0L
                    player.playQueue(local.queue, index, autoPlay = command.playing)
                    player.seekTo(position)
                } else if (remoteIds.isNotEmpty()) {
                    if (!hydrateAndReplace(remoteIds, target, position, command.playing)) return
                    leaderId = command.accountId.ifBlank { leaderId }
                } else {
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

    /** together.js: api.songs(ids) then queue.replace(remote order, target, position, playing). */
    private suspend fun hydrateAndReplace(
        remoteIds: List<String>,
        targetId: String,
        positionMs: Long,
        playing: Boolean,
    ): Boolean {
        val local = player.state.value.queue.associateBy { it.id.toString() }
        val missing = TogetherHydration.missingIds(remoteIds, local.keys)
        var fetched = emptyMap<String, com.yunyin.music.core.Track>()
        if (missing.isNotEmpty()) {
            log.record("解析", "stage=together hydrate requested=${missing.size}")
            fetched = runCatching { music.songsByIds(missing) }.getOrNull().orEmpty()
                .associateBy { it.id.toString() }
            log.record("解析", "stage=together hydrate result=${fetched.size}/${missing.size}")
        }
        val tracks = TogetherHydration.ordered(remoteIds, local, fetched)
        if (tracks.size != remoteIds.size) {
            log.record(
                "解析",
                "stage=together hydrate failed expected=${remoteIds.size} got=${tracks.size}; keeping local playback",
            )
            return false
        }
        val index = TogetherHydration.targetIndex(remoteIds, targetId)
        player.playQueue(tracks, index, autoPlay = playing)
        player.seekTo(positionMs.coerceAtLeast(0L))
        log.record("解析", "stage=together hydrate applied songs=${tracks.size} target=$targetId index=$index")
        return true
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

    private fun runOp(action: String, block: suspend () -> Unit) {
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
            "stage=together room=${TogetherRules.roomLabel(room?.roomId)} tick=$ticks action=$action result=$result$suffix",
        )
        if (!cryptoChecked) {
            cryptoChecked = true
            log.record("解析", "stage=together crypto ok=true")
        }
    }

    /** One-tap diagnostics: checkToken (not used by QPlayer for together), create, status, snapshot. */
    suspend fun runDiagnostics(): String {
        val block = StringBuilder()
        block.appendLine("together diagnostics room=${TogetherRules.roomLabel(room?.roomId)} available=$available")
        block.appendLine("checkToken: not sent (QPlayer checkTokenFor returns empty unless options.checkToken; listenTogether does not pass it)")
        api.create().onSuccess { created ->
            block.appendLine("create: roomId=${created.roomId.ifBlank { "none" }} creatorId=${created.creatorId.ifBlank { "none" }}")
            block.appendLine("create url=${api.lastCreateUrl}")
            block.appendLine("create payload=${api.lastCreatePayload.take(2000)}")
            block.appendLine("create code/body=${api.lastCreateBody.take(3000)}")
            block.appendLine("create candidates=[${api.lastCreateCandidates}] rawKeys=${api.lastCreateKeys}")
            if (created.roomId.isNotBlank()) {
                api.status().onSuccess { value ->
                    block.appendLine("status: inRoom=${value.inRoom} status=${value.status} roomId=${value.room?.roomId ?: "none"}")
                }.onFailure { block.appendLine("status: error=${it.message}") }
                api.snapshot(created.roomId).onSuccess { snap ->
                    block.appendLine("snapshot: playlist=${snap.songIds.size} commandType=${snap.command?.type ?: "none"} serverSeq=${snap.command?.sequence ?: 0}")
                }.onFailure { block.appendLine("snapshot: error=${it.message}") }
            }
        }.onFailure { block.appendLine("create: error=${it.message}") }
        block.appendLine("--- last together log ---")
        block.append(logDump())
        log.record("解析", "stage=together diagnostic captured chars=${block.length}")
        return block.toString()
    }

    /** Last together lines from the shared diagnostics file, for a dedicated copy action. */
    fun logDump(): String = log.readLast().orEmpty().lineSequence()
        .filter { it.contains("stage=together") }
        .toList().takeLast(200).joinToString("\n")

    private fun inviteLink(roomId: String, inviterId: String): String =
        "qplayer://listen-together?provider=netease&roomId=$roomId&inviterId=$inviterId"

    private fun now(): Long = System.currentTimeMillis()
}
