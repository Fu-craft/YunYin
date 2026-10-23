package com.yunyin.music.data.together

/*
 * Ported from qplayer-netease-plugin (MIT, Copyright © 2026 TIMER-err),
 * src/together.js + src/main.js + src/xeapi.js — reimplemented in Kotlin.
 */

/** One remote play command as returned inside the room snapshot. */
data class TogetherCommand(
    val accountId: String,
    val type: String,
    val formerSongId: String,
    val targetSongId: String,
    val progressMs: Long,
    val playing: Boolean,
    val sequence: Long,
)

data class TogetherSnapshot(
    val songIds: List<String>,
    val command: TogetherCommand?,
) {
    val signature: String get() = TogetherRules.commandSignature(command)
}

data class TogetherRoom(
    val roomId: String,
    val creatorId: String = "",
    val memberCount: Int = 0,
)

/** Recursive room extraction: the real create body nests the id deeper than `data.roomInfo`. */
data class RoomExtraction(val roomId: String, val creatorId: String, val candidates: List<String>)

object TogetherRoomParser {
    private val idPattern = Regex("[A-Za-z0-9_-]{8,128}_[0-9]{6,}")

    fun extract(body: org.json.JSONObject): RoomExtraction {
        val candidates = LinkedHashSet<String>()
        var creator = ""
        var memberCount = 0
        fun visit(value: Any?, key: String) {
            when (value) {
                is org.json.JSONObject -> {
                    value.optString("roomId").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    if (key.contains("room", ignoreCase = true)) {
                        value.optString("id").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    }
                    if (creator.isBlank()) {
                        creator = listOf("creatorId", "creatorAccountId", "creatorUserId")
                            .firstNotNullOfOrNull { value.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()
                    }
                    val users = value.optJSONArray("roomUsers") ?: value.optJSONArray("users")
                    if (users != null) memberCount = users.length()
                    value.keys().forEach { child -> visit(value.opt(child), child) }
                }
                is org.json.JSONArray -> (0 until value.length()).forEach { visit(value.opt(it), key) }
                is String -> {
                    idPattern.find(value)?.value?.let { candidates.add(it) }
                    if (value.trimStart().startsWith("{")) {
                        runCatching { visit(org.json.JSONObject(value), key) }
                    }
                }
            }
        }
        visit(body, "")
        return RoomExtraction(candidates.firstOrNull().orEmpty(), creator, candidates.toList().also {
            if (memberCount >= 0) Unit
        })
    }

    fun parse(body: org.json.JSONObject, fallbackId: String = ""): TogetherRoom {
        val ex = extract(body)
        val data = body.optJSONObject("data") ?: body
        val room = data.optJSONObject("roomInfo") ?: data
        val users = room.optJSONArray("roomUsers") ?: room.optJSONArray("users")
        return TogetherRoom(ex.roomId.ifBlank { fallbackId }, ex.creatorId, users?.length() ?: 0)
    }

    /** Human-readable server reason for the error message / log. */
    fun reason(body: org.json.JSONObject): String {
        val code = body.optInt("code", -1)
        val message = body.optString("message").ifBlank { body.optString("msg") }
        return "code=$code" + if (message.isNotBlank()) " msg=$message" else ""
    }
}

/** One local report, derived from a baseline/local playback comparison. */
sealed interface TogetherAction {
    data class ReplaceQueue(val songIds: List<String>, val version: Long) : TogetherAction
    data class Command(
        val type: String,
        val formerSongId: String,
        val targetSongId: String,
        val progressMs: Long,
        val playing: Boolean,
        val clientSeq: Long,
    ) : TogetherAction
}

/** Pure port of together.js decisions; all time values are milliseconds. */
/** Threading guard for MediaController/PlayerController access (Media3 requires the app main thread). */
object TogetherThreading {
    fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** Distinct classification so a wrong-thread failure is never mistaken for a network error. */
    fun classify(message: String?): String? {
        val value = message?.lowercase().orEmpty()
        return if (value.contains("wrong thread") || value.contains("mediacontroller") ||
            value.contains("main thread") || value.contains("main looper")
        ) "threading" else null
    }

    fun shouldWarnOffMain(isMainThread: Boolean): Boolean = !isMainThread
}

object TogetherRules {
    const val HEARTBEAT_TICKS = 8
    const val STATUS_TICKS = 12
    const val REDISCOVER_TICKS = 10
    const val REDISCOVER_MS = 5_000L
    const val LEADER_FALLBACK_MS = 3_500L
    const val POSITION_DELTA_MS = 1_000L
    const val BACKOFF_BASE_MS = 30_000L
    const val BACKOFF_MAX_MS = 120_000L
    const val HARD_STOP_FAILURES = 3

    fun commandSignature(command: TogetherCommand?): String = command?.let {
        "${it.type}|${it.formerSongId}|${it.targetSongId}|${it.progressMs}|${it.playing}|${it.sequence}"
    }.orEmpty()

    /**
     * together.js `isRateLimited` walks the error cause chain (depth 8) for these markers; our error already carries
     * the server message, so one string check is equivalent.
     */
    fun isRateLimited(message: String?): Boolean {
        val value = message?.lowercase().orEmpty()
        return value.contains("429") || value.contains("too many requests") ||
            value.contains("rate limit") || value.contains("操作频繁")
    }

    /** 30 s, 60 s, 120 s; null after the fourth failure (hard stop requiring rejoin). */
    fun backoffMs(failures: Int): Long? {
        if (failures > HARD_STOP_FAILURES) return null
        val shift = (failures - 1).coerceAtLeast(0)
        return minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl shift)
    }

    /** Freshness: ignore our own command; require a new signature and a not-older server sequence. */
    fun isFresh(
        command: TogetherCommand?,
        lastSignature: String,
        lastSequence: Long,
        ownAccountId: String,
    ): Boolean {
        if (command == null) return false
        if (command.accountId.isNotEmpty() && command.accountId == ownAccountId) return false
        return commandSignature(command) != lastSignature && command.sequence >= lastSequence
    }

    /** creator → lowest numeric account id → self. */
    fun leaderId(creatorId: String, memberIds: List<String>, ownAccountId: String): String {
        if (creatorId.isNotBlank()) return creatorId
        return memberIds.filter { it.toLongOrNull() != null }.minByOrNull { it.toLong() } ?: ownAccountId
    }

    /** A room is real only when the snapshot actually carries room data (playlist), not merely accept=200. */
    fun joinedFromSnapshot(songIds: List<String>): Boolean = songIds.isNotEmpty()

    /** A blank room id means create/accept failed: never enter, never report/end. */
    fun canEnter(roomId: String): Boolean = roomId.isNotBlank()

    fun roomLabel(roomId: String?): String =
        roomId?.takeIf { it.isNotBlank() } ?: "none reason=no-room-yet"

    /** `end/v2` is only legal for an explicit user action on a confirmed room. */
    fun canEnd(explicit: Boolean, roomId: String?): Boolean = explicit && !roomId.isNullOrBlank()

    /** Room id precedence: create response, then status; a snapshot with songs proves the room is real. */
    fun adoptRoomId(createdId: String, statusRoomId: String?, snapshotSongs: Int): String =
        createdId.ifBlank { statusRoomId.orEmpty() }.ifBlank { if (snapshotSongs > 0) statusRoomId.orEmpty() else "" }

    /** A negative status does not override a snapshot that actually contains songs. */
    fun preferSnapshot(snapshotSongs: Int, statusInRoom: Boolean?): Boolean = snapshotSongs > 0 && statusInRoom == false

    /** Creator enters as soon as the id is known; a joiner still needs a real snapshot. */
    fun creatorEnters(roomId: String): Boolean = roomId.isNotBlank()
    fun joinerEnters(roomId: String, playlist: Int): Boolean = roomId.isNotBlank() && playlist > 0

    /** Seed retry: one retry when the seeded snapshot is still empty. */
    fun shouldRetrySeed(playlist: Int, attempts: Int): Boolean = playlist <= 0 && attempts < 2

    /**
     * QPlayer's `checkTokenFor` returns "" unless `options.checkToken`; listenTogether calls do not pass it, so
     * QPlayer sends no X-antiCheatToken for together either. A token is only attached when one is present.
     */
    fun antiCheatHeader(token: String?): String? = token?.takeIf { it.isNotBlank() }

    fun idsSignature(ids: List<String>): String = ids.joinToString(",")

    /** Same real-session rule as subscribe/like-sync: cookie + non-anonymous. No numeric id is required. */
    fun available(cookie: String, anonymous: Boolean?): Boolean = cookie.isNotBlank() && anonymous != true

    /**
     * Local→report mapping. The queue change wins; a song change becomes GOTO (the reporter takes leadership);
     * otherwise a position jump becomes PROGRESS and a play/pause flip becomes PLAY/PAUSE.
     */
    fun planLocalChange(
        baselineQueue: List<String>,
        currentQueue: List<String>,
        baselineSongId: String,
        currentSongId: String,
        baselinePositionMs: Long,
        currentPositionMs: Long,
        baselinePlaying: Boolean,
        currentPlaying: Boolean,
        ownAccountId: String,
        version: Long,
        clientSeq: Long,
    ): TogetherAction? {
        if (idsSignature(baselineQueue) != idsSignature(currentQueue)) {
            return TogetherAction.ReplaceQueue(currentQueue, version)
        }
        if (baselineSongId != currentSongId && currentSongId.isNotBlank()) {
            return TogetherAction.Command(
                type = "GOTO",
                formerSongId = baselineSongId.ifBlank { "0" },
                targetSongId = currentSongId,
                progressMs = currentPositionMs.coerceAtLeast(0L),
                playing = currentPlaying,
                clientSeq = clientSeq,
            )
        }
        if (kotlin.math.abs(currentPositionMs - baselinePositionMs) >= POSITION_DELTA_MS) {
            return TogetherAction.Command(
                type = "PROGRESS",
                formerSongId = currentSongId,
                targetSongId = currentSongId,
                progressMs = currentPositionMs.coerceAtLeast(0L),
                playing = currentPlaying,
                clientSeq = clientSeq,
            )
        }
        if (baselinePlaying != currentPlaying) {
            return TogetherAction.Command(
                type = if (currentPlaying) "PLAY" else "PAUSE",
                formerSongId = currentSongId,
                targetSongId = currentSongId,
                progressMs = currentPositionMs.coerceAtLeast(0L),
                playing = currentPlaying,
                clientSeq = clientSeq,
            )
        }
        return null
    }

    /** qplayer://listen-together?... or a bare room id; same validation regexes as together.js. */
    fun parseInvite(raw: String): Result<Pair<String, String>> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("请输入邀请链接或房间 ID"))
        if (text.length > 1024) return Result.failure(IllegalArgumentException("邀请内容过长"))
        val query = if (text.contains('?')) text.substringAfter('?') else text
        val values = HashMap<String, String>()
        runCatching {
            query.split('&').forEach { part ->
                val at = part.indexOf('=')
                if (at > 0) values[decode(part.substring(0, at))] = decode(part.substring(at + 1))
            }
        }.getOrElse { return Result.failure(IllegalArgumentException("邀请链接编码无效")) }
        var roomId = values["roomId"].orEmpty()
        if (roomId.isEmpty() && !text.contains('=') && !text.contains('/')) roomId = text
        val inviterId = values["inviterId"].orEmpty()
        if (values["provider"].orEmpty().let { it.isNotEmpty() && it != "netease" }) {
            return Result.failure(IllegalArgumentException("这不是网易云一起听邀请"))
        }
        if (!Regex("^[A-Za-z0-9_-]{1,128}$").matches(roomId)) {
            return Result.failure(IllegalArgumentException("邀请链接或房间 ID 格式无效"))
        }
        if (inviterId.isNotEmpty() && inviterId.toLongOrNull() == null) {
            return Result.failure(IllegalArgumentException("邀请者 ID 格式无效"))
        }
        return Result.success(roomId to inviterId)
    }

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
}
