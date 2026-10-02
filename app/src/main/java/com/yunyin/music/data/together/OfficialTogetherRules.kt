package com.yunyin.music.data.together

/**
 * The official room's pure rules, kept out of [OfficialTogetherTransport] so they can be tested without
 * a network.
 *
 * Both of them encode a correction that is easy to lose in a refactor:
 *
 *  - **The invite is a pair.** A NetEase room id is a 43-character server string and it cannot be joined
 *    from the id alone — `/listentogether/accept` wants the inviter's id too. So the shareable invite is
 *    `roomId|inviterId`, and any code path that treats it as an opaque token survives only because the
 *    separator is preserved.
 *  - **A reported position is a snapshot.** `playCommand.progress` is the position *when the command was
 *    issued*. Treated as live, a peer who has been playing for a minute reads a minute behind, the drift
 *    correction seeks to catch up, and every poll repeats it — the desynchronised stutter that pollutes
 *    every naive listen-together implementation.
 */
object OfficialTogetherRules {

    /** What joins the two halves of an invite. */
    const val INVITE_SEP = "|"

    /**
     * Any `serverSeq` at or above this is a millisecond epoch, and so can date a command.
     *
     * The floor screens out a zero, a small counter, or anything else a server might put in the field:
     * ageing a position by "now minus 3" would add decades.
     */
    const val EPOCH_MS_FLOOR = 1_000_000_000_000L

    /** The furthest a reported position may be advanced — an hour, longer than any track. */
    const val MAX_ADVANCE_MS = 3_600_000L

    fun buildInvite(roomId: String, inviterId: Long): String = "$roomId$INVITE_SEP$inviterId"

    /** The two halves of an invite, or null when the input is not one. */
    fun parseInvite(code: String): Pair<String, Long>? {
        val parts = code.trim().split(INVITE_SEP)
        if (parts.size != 2) return null
        val room = parts[0].trim()
        val inviter = parts[1].trim().toLongOrNull() ?: return null
        if (room.isBlank() || inviter == 0L) return null
        return room to inviter
    }

    /**
     * Normalises a pasted invite without corrupting it.
     *
     * Uppercasing is wrong here twice over: a room id is lowercase hex, and the separator is data, not
     * punctuation. What is normalised instead is the *separator* — a share sheet or a chat app may turn
     * `|` into a space, a colon, or a newline, and any of those arriving as the pair should still join.
     * A `-` is deliberately not accepted as a separator, because a hyphen is not a legal character in a
     * room id and a wrong split would produce a room that simply does not exist.
     */
    fun normaliseJoinInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains(INVITE_SEP)) return trimmed
        val parts = trimmed.split(' ', ':', '\uFF1A', '\n', '\r', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size == 2) parts.joinToString(INVITE_SEP) else trimmed
    }

    /**
     * The position a command's playback has reached by [nowMs].
     *
     * Only a *playing* command ages: a paused one has a position that does not move, so advancing it
     * would invent drift out of nothing.
     */
    fun playbackAdvance(
        positionMs: Long,
        playing: Boolean,
        serverSeq: Long,
        nowMs: Long,
    ): Long {
        if (!playing) return positionMs
        // No usable timestamp: hand back the raw value rather than guessing an age for it.
        if (serverSeq < EPOCH_MS_FLOOR) return positionMs
        val elapsed = (nowMs - serverSeq).coerceIn(0L, MAX_ADVANCE_MS)
        return positionMs + elapsed
    }
}
