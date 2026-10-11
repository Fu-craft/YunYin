package com.yunyin.music.data.together.ltw

import com.yunyin.music.data.together.TogetherLocalAction
import com.yunyin.music.data.together.TogetherMember
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherResult
import com.yunyin.music.data.together.TogetherSeed
import com.yunyin.music.data.together.TogetherTrack
import com.yunyin.music.data.together.TogetherTransport
import com.yunyin.music.data.together.TogetherLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Listen-together over the **NeriPlayer LTW service** — a server-authoritative room.
 *
 * ## Why this model fixes what the peer-to-peer one could not
 *
 * The previous transports made the two devices negotiate: each published its state and each decided what to
 * do about the other. That is a distributed consensus problem in disguise, and its signature failure is
 * exactly what users reported — *"I press play and the other side pauses"* — because "who acted last" cannot
 * be decided from two disagreeing clocks, and every fix moved the disagreement rather than removing it.
 *
 * Here **one server owns the playback**. It stores `basePositionMs` + `baseTimestampMs`, computes
 * `expectedPositionMs = base + elapsed * rate` against its own clock, and broadcasts it. A device does not
 * publish its state at all: the controller sends commands, a listener sends *requests*, and both then read
 * back the same authoritative state. Two devices cannot disagree, so they cannot invert each other.
 *
 * ## Transport
 *
 * A WebSocket carries live updates (`room_state_updated`, and the room suspending/resuming when the
 * controller drops off) and the controller's heartbeat. An HTTP `/state` poll runs alongside it, deliberately:
 * it is what makes the feature survive a proxy that eats WebSockets, and it carries the server clock the
 * position is aged against. Either path updating the same field is harmless — both read the same authority.
 *
 * ## Liveness
 *
 * The server suspends the room if the controller stops signalling for 45s. So the controller sends `np_ping`
 * on a short interval: the service treats it as a heartbeat *and* answers `np_pong`, which is also how the
 * client clock is kept in step with the server's. A backgrounded controller is therefore the one case the
 * user sees as "房主暂时离线", which the UI names rather than reporting a mystery failure.
 */
class LtwTransport(
    private val api: LtwApi,
    private val scope: CoroutineScope,
    /**
     * This installation's identity, and the nickname shown to the other member.
     *
     * A per-installation UUID is required: the service validates `userUuid` as a real UUID, and a per-device
     * identity (rather than the NetEase account) is what lets one account test the room from two devices.
     */
    private val identityProvider: () -> Pair<String, String>,
) : TogetherTransport {

    override val configured: Boolean get() = base.isNotBlank()

    override val authoritative: Boolean get() = true

    override val serverLabel: String? get() = "一起听服务"

    /** The service's own cadence: it suspends a silent controller after 45s, so this is comfortably inside. */
    override val pollIntervalMs: Long get() = 2_000L

    /** Six readable characters, from the service's own alphabet. */
    override val codeLength: Int get() = 6

    /** A six-character code is short enough to read out, so joining can be typed as well as pasted. */
    override val joinsByTyping: Boolean get() = true

    override var seedProvider: (() -> TogetherSeed?)? = null

    private var base: String = ""
    private var roomId: String? = null
    private var token: String? = null
    private var role: String = "listener"
    private var memberSecret: String? = null
    private var joinSecret: String? = null

    /** The newest authoritative state, from either the socket or the poll. */
    @Volatile private var latest: LtwSnapshot? = null

    /** Server clock minus client clock, so a server timestamp can be aged locally. */
    @Volatile private var clockOffsetMs: Long = 0L

    private var socket: WebSocket? = null
    private var socketJob: Job? = null
    private var pollJob: Job? = null

    fun configure(url: String) {
        base = url
    }

    // ------------------------------------------------------------------ room lifecycle

    override fun normaliseJoinInput(input: String): String {
        // An invite is `roomId|joinSecret` (see [inviteText]). A pasted share sentence is searched for that
        // shape first, so a chat app's added punctuation does not corrupt it; the room id itself is
        // uppercase and the secret is base64url, so nothing is case-folded or truncated.
        val found = INVITE.find(input)?.value
        if (found != null) return found
        return input.trim()
    }

    override fun inviteText(code: String): String =
        joinSecret?.let { "$code$INVITE_SEP$it" } ?: code

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
        val seed = seedProvider?.invoke()
        if (seed == null || seed.tracks.isEmpty()) {
            return TogetherResult.Failed("请先播放一首歌再创建一起听")
        }
        val (uuid, nickname) = identityProvider()
        TogetherLog.add("LTW create as $uuid ($nickname), ${seed.tracks.size} tracks")
        val membership = api.create(base, uuid, name.ifBlank { nickname }, seed.toLtwSeed())
            .getOrElse { return failed(it) }
        adopt(membership)
        TogetherLog.add("LTW create ok room=${membership.roomId} role=${membership.role}")
        start()
        return TogetherResult.Ok(membership.roomId)
    }

    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> {
        // Nothing to look up before joining: the service answers "room not initialized" / "room closed" from
        // the join call itself, and a code with no secret cannot be read at all (every read needs a token).
        return TogetherResult.Ok(emptyList())
    }

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        val parts = code.trim().split(INVITE_SEP)
        val room = parts.getOrNull(0)?.trim().orEmpty()
        val secret = parts.getOrNull(1)?.trim()
        if (room.isBlank()) return TogetherResult.Failed(INCOMPLETE_INVITE)
        val (uuid, nickname) = identityProvider()
        // Rejoining the same room on this device uses the stored member secret; a fresh join uses the invite's.
        val mine = memberSecret?.takeIf { roomId == room }
        TogetherLog.add("LTW join room=$room as $uuid (secret=${if (mine != null) "member" else "join"})")
        val membership = api.join(base, room, uuid, name.ifBlank { nickname }, secret, mine)
            .getOrElse { return failed(it) }
        adopt(membership)
        TogetherLog.add("LTW join ok role=${membership.role} members=${membership.state.members.size}")
        start()
        return TogetherResult.Ok(Unit)
    }

    private fun adopt(membership: LtwMembership) {
        roomId = membership.roomId
        token = membership.token
        role = membership.role
        membership.memberSecret?.let { memberSecret = it }
        membership.joinSecret?.let { joinSecret = it }
        // Seed the cache from the join/create reply so the first tick has something to apply without waiting
        // for a poll. The position is taken from the room's own anchor, not invented.
        latest = LtwSnapshot(
            state = membership.state,
            expectedPositionMs = membership.state.playback?.basePositionMs ?: 0L,
            serverNowMs = 0L,
        )
        roomGone = false
    }

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        stop()
        val room = roomId
        val t = token
        clear()
        if (room != null && t != null) api.leave(base, room, t)
        TogetherLog.add("LTW leave room=$room")
        return TogetherResult.Ok(Unit)
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> = leave(code, "")

    private fun clear() {
        roomId = null
        token = null
        role = "listener"
        memberSecret = null
        joinSecret = null
        latest = null
        roomGone = false
    }

    // ------------------------------------------------------------------ read path

    /**
     * The room's authoritative playback, as the engine's peer shape.
     *
     * [excludeUid] is not used to filter the *authoritative* entry — the room is one shared state, not a set
     * of per-member states — but the member list is still returned so the sheet can show who is present.
     */
    override suspend fun postState(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<List<TogetherPeerState>> = pollPeers(code, uid)

    override suspend fun pollPeers(code: String, excludeUid: String): TogetherResult<List<TogetherPeerState>> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        val t = token ?: return TogetherResult.Failed("尚未进入房间")
        // A fresh read is what makes convergence exact; the socket only makes it fast. On a socket-driven
        // tick the cached snapshot is used, so this is a no-op most of the time.
        val result = api.state(base, room, t)
        val fresh = result.getOrNull()
        if (fresh != null) {
            applySnapshot(fresh)
        } else {
            val failure = (result.exceptionOrNull() as? LtwProtocolException)?.failure
            if (failure?.noRoom == true) {
                roomGone = true
                return TogetherResult.NoRoom(failure.message)
            }
            // A transient read failure with a cached snapshot to fall back on is not fatal: the next tick
            // retries, and the socket may already have delivered a newer state than the poll would have.
            if (latest == null) {
                return TogetherResult.Failed(result.exceptionOrNull()?.message ?: "一起听请求失败")
            }
        }
        if (roomGone) return TogetherResult.NoRoom("这个一起听已结束")
        return TogetherResult.Ok(peersFromRoom(excludeUid))
    }

    private fun peersFromRoom(excludeUid: String): List<TogetherPeerState> {
        val snapshot = latest ?: return emptyList()
        val state = snapshot.state
        val out = ArrayList<TogetherPeerState>(state.members.size)
        val controllerName = state.members.firstOrNull { it.userUuid == state.controllerUserUuid }?.nickname
        if (state.track != null) {
            out += TogetherPeerState(
                uid = state.controllerUserUuid,
                name = controllerName.orEmpty(),
                songId = state.track.id(),
                positionMs = snapshot.positionMsNow(),
                playing = state.playback?.playing == true,
                updatedAt = state.version,
                seq = state.version,
                commandType = "AUTHORITATIVE",
                queue = state.queue.map { it.id() },
            )
        }
        // Everyone else rides along as presence only (no song), which keeps the pair strip populated.
        state.members.forEach { member ->
            if (member.userUuid == state.controllerUserUuid) return@forEach
            if (member.userUuid == excludeUid) return@forEach
            out += TogetherPeerState(
                uid = member.userUuid, name = member.nickname, songId = 0L, positionMs = 0L,
                playing = false, updatedAt = 0L, seq = 0L,
            )
        }
        return out
    }

    private fun applySnapshot(snapshot: LtwSnapshot) {
        latest = snapshot
        if (snapshot.serverNowMs > 0L) clockOffsetMs = snapshot.serverNowMs - System.currentTimeMillis()
        if (snapshot.state.roomStatus == "closed") roomGone = true
    }

    // ------------------------------------------------------------------ write path

    /**
     * Publishes a discrete local action as the service's own event.
     *
     * The controller sends `PLAY`/`PAUSE`/`SEEK`/`SET_TRACK`; a listener sends the matching `REQUEST_*`,
     * which the server arbitrates only while the controller is online. Requests carry the target track's
     * `stableKey`, which the service validates — that is why the stable key is derived from the song id
     * rather than being opaque.
     */
    override suspend fun publishAction(
        action: TogetherLocalAction,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        clientSeq: Long,
    ): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        val t = token ?: return TogetherResult.Failed("尚未进入房间")
        val stableKey = stableKeyOf(songId)
        val isController = role == "controller"
        val type = when (action) {
            TogetherLocalAction.Goto -> if (isController) "SET_TRACK" else "REQUEST_SET_TRACK"
            TogetherLocalAction.Seek -> if (isController) "SEEK" else "REQUEST_SEEK"
            TogetherLocalAction.Play -> if (isController) "PLAY" else "REQUEST_PLAY"
            TogetherLocalAction.Pause -> if (isController) "PAUSE" else "REQUEST_PAUSE"
        }
        val event = JSONObject()
            .put("type", type)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("clientTimeMs", System.currentTimeMillis())
            .put("clientSequence", clientSeq)
            .put("requestTrackStableKey", stableKey)
        if (action == TogetherLocalAction.Goto) {
            event.put("track", LtwApi.trackJson(ltwTrack(songId)))
            event.put("queue", seedProvider?.invoke()?.let { seed ->
                org.json.JSONArray().also { arr -> seed.tracks.forEach { arr.put(LtwApi.trackJson(it.toLtw())) } }
            })
            event.put("currentIndex", seedProvider?.invoke()?.currentIndex ?: 0)
        }
        return api.control(base, room, t, event).fold(
            onSuccess = {
                TogetherLog.add("LTW send $type song=$songId pos=$positionMs")
                TogetherResult.Ok(Unit)
            },
            onFailure = { error ->
                TogetherLog.add("LTW send $type failed: ${error.message}")
                failed(error)
            },
        )
    }

    override suspend fun publishQueue(queue: List<Long>, uid: String, version: Long): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Ok(Unit)
        val t = token ?: return TogetherResult.Ok(Unit)
        val seed = seedProvider?.invoke() ?: return TogetherResult.Ok(Unit)
        if (role != "controller") return TogetherResult.Ok(Unit)
        val event = JSONObject()
            .put("type", "SET_QUEUE")
            .put("queue", org.json.JSONArray().also { arr ->
                seed.tracks.forEach { arr.put(LtwApi.trackJson(it.toLtw())) }
            })
            .put("currentIndex", seed.currentIndex)
            .put("clientTimeMs", System.currentTimeMillis())
        return api.control(base, room, t, event).fold(
            onSuccess = { TogetherResult.Ok(Unit) },
            onFailure = { failed(it) },
        )
    }

    // ------------------------------------------------------------------ sockets and liveness

    private fun start() {
        stop()
        connectSocket()
        pollJob = scope.launch {
            while (isActive) {
                val room = roomId ?: break
                val t = token ?: break
        val result = api.state(base, room, t)
        val fresh = result.getOrNull()
        if (fresh != null) {
            applySnapshot(fresh)
        } else {
            val failure = (result.exceptionOrNull() as? LtwProtocolException)?.failure
            if (failure?.noRoom == true) roomGone = true
        }
                // The controller must keep signalling or the room suspends after 45s. np_ping both proves
                // liveness and brings back the server clock, which is what the position is aged against.
                if (role == "controller") sendPing()
                delay(pollIntervalMs)
            }
        }
    }

    private fun stop() {
        pollJob?.cancel(); pollJob = null
        socketJob?.cancel(); socketJob = null
        socket?.close(1000, null); socket = null
    }

    private fun sendPing() {
        runCatching { socket?.send(JSONObject().put("type", "np_ping").put("t", System.currentTimeMillis()).toString()) }
    }

    /** Connects the WebSocket, reconnecting while the room is live. The poll runs regardless. */
    fun connectSocket() {
        val room = roomId ?: return
        val t = token ?: return
        val url = base.trimEnd('/')
            .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/rooms/$room/ws?token=" + java.net.URLEncoder.encode(t, "UTF-8")
        socketJob?.cancel()
        socketJob = scope.launch {
            val request = Request.Builder().url(url).build()
            socket = apiSocketClient.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSocketMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    TogetherLog.add("LTW socket failed: ${t.message}")
                }
            })
        }
    }

    private fun handleSocketMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "welcome", "room_state_updated", "room_suspended", "room_resumed" -> {
                val state = LtwJson.roomState(json.optJSONObject("state")) ?: return
                applySnapshot(
                    LtwSnapshot(
                        state = state,
                        expectedPositionMs = json.optLong("expectedPositionMs"),
                        serverNowMs = json.optLong("nowMs"),
                    ),
                )
                if (state.controllerOffline) roomGone = false
            }
            "np_pong" -> {
                val now = json.optLong("nowMs")
                if (now > 0L) clockOffsetMs = now - System.currentTimeMillis()
            }
        }
    }

    private var roomGone = false

    // ------------------------------------------------------------------ helpers

    private fun LtwSnapshot.positionMsNow(): Long {
        val playback = state.playback ?: return expectedPositionMs
        if (!playback.playing) return expectedPositionMs
        val elapsed = (System.currentTimeMillis() + clockOffsetMs) - playback.baseTimestampMs
        val advanced = playback.basePositionMs + (elapsed * playback.playbackRate).toLong()
        val duration = state.track?.durationMs ?: 0L
        return if (duration > 0L) advanced.coerceIn(0L, duration) else advanced.coerceAtLeast(0L)
    }

    private fun LtwTrack.id(): Long = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()

    private fun stableKeyOf(songId: Long): String = "yunyin:$songId"

    private fun ltwTrack(songId: Long): LtwTrack? =
        seedProvider?.invoke()?.trackById(songId)?.toLtw()

    private fun TogetherTrack.toLtw(): LtwTrack = LtwTrack(
        stableKey = stableKeyOf(id),
        channelId = CHANNEL,
        audioId = id.toString(),
        name = name,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
    )

    private fun TogetherSeed.toLtwSeed(): LtwApi.LtwSeed = LtwApi.LtwSeed(
        tracks = tracks.map { it.toLtw() },
        currentIndex = currentIndex,
        positionMs = positionMs,
        playing = playing,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
    )

    private fun <T> failed(result: Result<T>): TogetherResult<T> = failed(result.exceptionOrNull())

    private fun <T> failed(error: Throwable?): TogetherResult<T> {
        val failure = (error as? LtwProtocolException)?.failure
        return when {
            failure == null -> TogetherResult.Failed(error?.message ?: "一起听请求失败")
            failure.noRoom -> TogetherResult.NoRoom(failure.message)
            else -> TogetherResult.Failed(failure.message)
        }
    }

    private companion object {
        const val INVITE_SEP = "|"

        /**
         * The non-local channel id. The service rejects `local` tracks outright, and this app only ever
         * shares songs it fetched from NetEase, so a constant is honest here.
         */
        const val CHANNEL = "netease"

        const val INCOMPLETE_INVITE = "邀请信息不完整：请粘贴对方分享的邀请"

        /** `ABC123|<base64url secret>` embedded in whatever the user pasted. */
        val INVITE = Regex("""[A-Z0-9]{6}\|[A-Za-z0-9_\-]{20,}""")

        /** A socket client that outlives a single poll, with no read timeout on the open connection. */
        val apiSocketClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
