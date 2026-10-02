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

    /**
     * The invite inside whatever the peer actually sent, or null when there is none.
     *
     * ## Why this is needed, and what it fixes
     *
     * The share action hands a whole sentence to the share sheet — "和我一起听歌吧，房间码：<roomId>|<inviterId>"
     * — because that is what reads well in a chat. The peer then pastes **that sentence** into the join
     * field, and the parser split it on `|`: the first half became "和我一起听歌吧，房间码：<roomId>", which is
     * not a room id, so `/listentogether/accept` was called with a room that does not exist. Joining failed
     * for a reason nothing in the UI could show.
     *
     * So the invite is *found* in the text rather than assumed to be the whole of it. The shape it looks for
     * is the one the server actually issues: 32 hex characters, an underscore, the create time in seconds,
     * then the inviter's numeric id after the separator —
     * `6be6cda6f9b0ff91233f42baf6c79d5d_1790909976|17583419505`. Matching that exactly means a sentence
     * around it, a line break, or a chat app's added punctuation all still resolve to the same pair.
     */
    fun extractInvite(text: String): String? =
        INVITE_PATTERN.find(text)?.value

    /**
     * The server's room id, from a room's own command or invite.
     *
     * Exposed because the same id has to be recognised in more than one place, and a second hand-written
     * copy of the pattern is how the two would drift.
     */
    val INVITE_PATTERN = Regex("""[0-9a-fA-F]{16,}_\d{6,}\|\d{5,}""")

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
     *
     * A complete invite embedded in a sentence is extracted first (see [extractInvite]); only text with no
     * invite in it is treated as a bare pair.
     */
    fun normaliseJoinInput(input: String): String {
        extractInvite(input)?.let { return it }
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
