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
)

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
