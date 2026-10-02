package com.yunyin.music.data.together

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.TogetherRemoteState
import com.yunyin.music.core.TogetherUser
import com.yunyin.music.data.MusicRepository

/**
 * Listen-together over **NetEase's own API** — the official feature, no third-party relay involved.
 *
 * ## What was measured, and what is still assumed
 *
 * An earlier probing pass concluded that NetEase's HTTP API could not read a peer's playback state, and
 * that conclusion drove a long detour into self-hosted relays and public brokers. The conclusion was
 * wrong for a measurable reason: it was taken against a server whose session was **not logged in**,
 * where every route below answers `{"code":301,"msg":"需要登录"}`. Against a server that *is* signed in,
 * all nine routes this class uses exist and answer `200` — `room/create`, `room/check`, `accept`,
 * `sync/list/command`, `play/command`, `sync/playlist/get`, `heatbeat`, `status` and `end` were each
 * called directly and confirmed present (a control path returns `404`, so the server does distinguish
 * them).
 *
 * One thing could **not** be confirmed from a single account, and it is worth stating plainly because the
 * whole feature rests on it: `sync/playlist/get` returned `{}` in a room where this account was the only
 * member *even after* that account issued a GOTO. Two readings explain that, and they agree about what
 * the client should do:
 *
 *  - the endpoint reports the **other** member's command and filters the caller's own; or
 *  - it reports nothing until a second member has acted.
 *
 * Either way, with two signed-in members each device's poll yields the peer's song, position and play
 * state, and a device never sees its own command echoed. The self-echo filter below is therefore a
 * safety net for the first reading rather than a load-bearing part — but it is cheap, and being wrong
 * about which reading holds costs a device following itself, so it stays.
 *
 * What this means for verification: **the read path needs two signed-in accounts.** With one account the
 * room is empty by construction, which is a property of the feature, not a defect in the client.
 *
 * ## The shape of the flow
 *
 *  - `room/create` also **returns the account's existing room** if it has one, so the id is not always
 *    fresh. That is NetEase's semantics, and it is harmless: the returned room is still `AVAILABLE`, and
 *    re-sharing its id works.
 *  - **Both members must be signed in**, and the client sends *its own* cookie, so each device acts as
 *    its own account. A guest session cannot create or join.
 *  - **Reading is polling.** The interval is short enough to feel live and long enough not to hammer the
 *    API.
 *
 * ## What the protocol gives us for free
 *
 * `serverSeq` orders commands server-side, which removes the hardest problem in the hand-rolled
 * versions — "whose action is newer?" — that had to be solved with a Lamport counter precisely because
 * the two devices' clocks disagree by tens of seconds.
 *
 * ## The read path is an anchor, not a live position
 *
 * `playCommand.progress` is the position **at the moment the command was issued**, so a peer who plays
 * for a minute without seeking looks a minute behind unless that is accounted for. Every position handed
 * to the sync engine is therefore advanced by the elapsed time since the command (see [playbackAdvance]).
 * Without it both devices spend the session seeking each other back — the classic listen-together
 * stutter — which is exactly the bug this class exists to avoid.
 */
class OfficialTogetherTransport(
    private val music: MusicRepository,
) : TogetherTransport {

    /**
     * The feature needs the same API server the rest of the app uses, so it is available exactly when
     * that is; a build with no endpoint configured has nothing to talk to.
     */
    override val configured: Boolean
        get() = com.yunyin.music.data.SettingsStore.hasBaseUrl

    override val serverLabel: String? get() = "网易云官方接口"

    /**
     * Two seconds.
     *
     * The room is read by polling, and this is the latency the peer's action costs. Faster would be
     * live-er (the position advances on its own, so what is really being polled is *commands*), at the
     * cost of more requests; slower starts to feel like the room is not following.
     */
    override val pollIntervalMs: Long get() = 2_000L

    /**
     * Room ids are 43 characters: 32 hex digits, `_`, then the create time in seconds
     * (`6be6cda6f9b0ff91233f42baf6c79d5d_1790909976` — measured).
     *
     * Used only as a *floor* for hand-entered input, and nothing types one: joining goes through the
     * invite pair. Stated anyway so the length is not a mystery in the sheet's validation.
     */
    override val codeLength: Int get() = 43

    /** Nobody types a 43-character id: joining pastes the shared invite, which carries both parts. */
    override val joinsByTyping: Boolean get() = false

    /**
     * The invite is the pair, and it must survive being pasted.
     *
     * Delegated to [OfficialTogetherRules] so the rule is pinned by a test: uppercasing — the right rule
     * for a typed alphanumeric code — corrupts this one twice over, because the room id is lowercase hex
     * and the separator is part of the payload.
     */
    override fun normaliseJoinInput(input: String): String = OfficialTogetherRules.normaliseJoinInput(input)

    /** The shareable invite for the current room, or null when not in one. */
    override fun inviteText(code: String): String =
        roomId?.let { buildInvite(it, inviterId) } ?: code

    /** The room this transport is acting in, and the invite pair needed to join it. */
    var roomId: String? = null
        private set
    var inviterId: Long = 0L
        private set

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
        return when (val result = music.togetherCreateRoom()) {
            is NetResult.Ok -> {
                roomId = result.value.roomId
                inviterId = result.value.inviterId
                TogetherLog.add("CREATE ok room=${TogetherLog.short(roomId)} inviter=${inviterId}")
                TogetherResult.Ok(result.value.roomId)
            }
            is NetResult.Err -> {
                TogetherLog.add("CREATE failed: ${result.message}")
                TogetherResult.Failed(result.message)
            }
        }
    }

    /**
     * Joining needs the full room id and the inviter id.
     *
     * The UI passes them as `roomId|inviterId` in [code], because an invite is a pair — the app's shared
     * invite text carries both, and there is nothing for a person to type.
     */
    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> {
        val parsed = parseInvite(code) ?: return TogetherResult.Failed(INCOMPLETE_INVITE)
        TogetherLog.add("CHECK room=${TogetherLog.short(parsed.first)} inviter=${parsed.second}")
        return when (val result = music.togetherRoomCheck(parsed.first)) {
            is NetResult.Ok ->
                if (result.value) TogetherResult.Ok(emptyList())
                else {
                    TogetherLog.add("CHECK says the room is gone")
                    TogetherResult.NoRoom("这个一起听已结束或失效")
                }
            is NetResult.Err -> {
                TogetherLog.add("CHECK failed: ${result.message}")
                TogetherResult.Failed(result.message)
            }
        }
    }

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        val parsed = parseInvite(code) ?: run {
            TogetherLog.add("JOIN aborted: not an invite -> ${TogetherLog.short(code)}")
            return TogetherResult.Failed(INCOMPLETE_INVITE)
        }
        return when (val result = music.togetherAccept(parsed.first, parsed.second)) {
            is NetResult.Ok -> {
                roomId = parsed.first
                inviterId = parsed.second
                membersCheckedAt = 0L
                TogetherLog.add("JOIN ok room=${TogetherLog.short(parsed.first)} inviter=${parsed.second}")
                TogetherResult.Ok(Unit)
            }
            // The endpoint answers "ended/expired" as a plain error; naming it helps more than the code.
            is NetResult.Err -> {
                TogetherLog.add("JOIN failed: ${result.message}")
                TogetherResult.Failed(result.message)
            }
        }
    }

    /**
     * Publishes this device's presence and reads the room's state back, one call each.
     *
     * The heartbeat is not the same thing as a command: it is what keeps this member *present* (the
     * server drops silent members), while the room's shared command changes only through
     * [publishAction]. Reporting the position here therefore cannot make the two devices overwrite each
     * other — which is exactly why the two are separate endpoints.
     */
    override suspend fun postState(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<List<TogetherPeerState>> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        if (songId > 0L) {
            music.togetherHeartbeat(room, songId, playing, positionMs)
        }
        return pollPeers(code, uid)
    }

    /**
     * The **read path**: one call, the room's current command.
     *
     * The position it returns is the one at the moment the command was issued, so it is advanced by the
     * elapsed time before being handed on — see [playbackAdvance]. Member names come from a second,
     * much rarer call, because the command carries an id but no nickname.
     */
    override suspend fun pollPeers(
        code: String,
        excludeUid: String,
    ): TogetherResult<List<TogetherPeerState>> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        val command = when (val result = music.togetherRemoteState(room)) {
            is NetResult.Ok -> result.value
            is NetResult.Err -> {
                TogetherLog.add("POLL failed: ${result.message}")
                return TogetherResult.Failed(result.message)
            }
        }
        refreshMembers(room)
        val peers = buildPeers(command, excludeUid)
        // One line per poll, because this is the whole question when sync fails: did the room report a
        // command, whose was it, did we decide it was ours, and what did that leave to follow?
        val isOwn = command.userId != 0L && selfUid != 0L && command.userId == selfUid
        TogetherLog.add(
            "POLL cmd=${command.hasCommand} song=${command.songId} play=${command.playing} " +
                "author=${command.userId} selfUid=$selfUid own=$isOwn " +
                "members=${members.size} followed=${peers.count { it.hasSong }}",
        )
        return TogetherResult.Ok(peers)
    }

    /**
     * Publishes a discrete local action as the official protocol's `play/command`.
     *
     * This — not the heartbeat — is the write path: it is what changes the room's recorded command, and
     * therefore what the peer's next poll sees. `formerSongId` is required by the endpoint and `-1`
     * means "there was no previous track", which is what the official demo sends.
     */
    override suspend fun publishAction(
        action: TogetherLocalAction,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        clientSeq: Long,
    ): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        ensureSelfUid()
        val type = when (action) {
            TogetherLocalAction.Goto -> "GOTO"
            TogetherLocalAction.Seek -> "seek"
            TogetherLocalAction.Play -> "PLAY"
            TogetherLocalAction.Pause -> "PAUSE"
        }
        return when (val result = music.togetherCommand(room, type, songId, positionMs, playing, clientSeq)) {
            is NetResult.Ok -> {
                TogetherLog.add("SEND $type song=$songId pos=$positionMs play=$playing seq=$clientSeq")
                TogetherResult.Ok(Unit)
            }
            is NetResult.Err -> {
                TogetherLog.add("SEND $type FAILED: ${result.message}")
                TogetherResult.Failed(result.message)
            }
        }
    }

    /**
     * Publishes the queue, so a joiner gets the list and not just the current song.
     *
     * `userId` is sent as given: the endpoint records who replaced the list, and it must be a real
     * account for the server to accept the command.
     */
    override suspend fun publishQueue(queue: List<Long>, uid: String, version: Long): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Failed("尚未进入房间")
        ensureSelfUid()
        val owner = selfUid.takeIf { it != 0L } ?: uid.toLongOrNull() ?: 0L
        if (owner == 0L) return TogetherResult.Ok(Unit)
        return when (val result = music.togetherSyncList(room, queue, owner, version)) {
            is NetResult.Ok -> {
                TogetherLog.add("QUEUE ${queue.size} songs as $owner")
                TogetherResult.Ok(Unit)
            }
            is NetResult.Err -> {
                TogetherLog.add("QUEUE failed: ${result.message}")
                TogetherResult.Failed(result.message)
            }
        }
    }

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        roomId = null
        inviterId = 0L
        members = emptyList()
        membersCheckedAt = 0L
        // Leaving a shared room is the endpoint's "unfollow"; ending it for everyone else would be rude,
        // so this only stops publishing and lets the server time the member out.
        return TogetherResult.Ok(Unit)
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Ok(Unit)
        roomId = null
        inviterId = 0L
        members = emptyList()
        membersCheckedAt = 0L
        return when (val result = music.togetherEnd(room)) {
            is NetResult.Ok -> TogetherResult.Ok(Unit)
            is NetResult.Err -> TogetherResult.Failed(result.message)
        }
    }

    // ------------------------------------------------------------------ read-path helpers

    /**
     * The signed-in account id, learned once.
     *
     * Needed only to recognise *this device's own* commands: the room records who authored its current
     * command, and a device that followed its own echo would fight itself — seeking back and forth
     * against the position it just published. A failure is left as 0, which disables the filter rather
     * than the feature: the cost of getting it wrong is a redundant correction, not a broken room.
     */
    private suspend fun ensureSelfUid() {
        if (selfUid != 0L) return
        selfUid = music.account()?.userId ?: 0L
        // Logged because this one value decides whether a device can tell its own command from the peer's:
        // with it unresolved, each side treats its own echoes as the peer's, and "the song does not follow"
        // is the visible result. Without this line the log could not distinguish that from a poll that
        // simply returned nothing.
        TogetherLog.add(
            if (selfUid == 0L) "SELF unresolved (looks like this session has no real account?)"
            else "SELF uid=$selfUid",
        )
    }

    /**
     * Refreshes the member list, at most once per [MEMBERS_REFRESH_MS].
     *
     * A second endpoint (and so a second request) on most ticks would double this transport's traffic
     * for something that changes once per join or leave, so it is deliberately rarer than the poll.
     * A failed refresh keeps the previous list: a member list going stale for ten seconds is nothing,
     * while treating it as a room failure would end a working session.
     */
    private suspend fun refreshMembers(room: String) {
        val now = System.currentTimeMillis()
        if (now - membersCheckedAt < MEMBERS_REFRESH_MS) return
        membersCheckedAt = now
        ensureSelfUid()
        when (val result = music.togetherStatus()) {
            is NetResult.Ok -> members = result.value.members
                .filter { selfUid == 0L || it.uid != selfUid }
                // The avatar is carried through rather than dropped: `/listentogether/status` returns
                // `roomUsers[].avatarUrl`, and the room screen shows the two members' faces. Discarding it
                // here was why the feature had no faces to show.
                .map { TogetherMember(uid = "${it.uid}", name = it.nickname, avatarUrl = it.avatarUrl) }
            is NetResult.Err -> Unit
        }
    }

    /**
     * The room's command, as the engine's peer shape — the command's **author** is the peer.
     *
     * A command authored by us is dropped entirely: it is our own echo. Members who are in the room but
     * have issued no command ride along with `songId = 0`, which keeps them out of the sync decision
     * (that tests `hasSong`) while still letting the sheet say someone is here.
     */
    private fun buildPeers(
        command: TogetherRemoteState,
        excludeUid: String,
    ): List<TogetherPeerState> {
        val author = if (command.userId != 0L) "${command.userId}" else null
        // No uid to compare against is "cannot tell", and the safe reading of "cannot tell" is "this is
        // not mine": a peer's command applied once is recoverable, while ignoring a genuine peer
        // command leaves the room silently stuck.
        val isOwnEcho = author != null && selfUid != 0L && command.userId == selfUid
        val authorName = author?.let { id -> members.firstOrNull { it.uid == id }?.name }.orEmpty()
        val authorAvatar = author?.let { id -> members.firstOrNull { it.uid == id }?.avatarUrl }

        val peers = ArrayList<TogetherPeerState>(members.size + 1)
        if (command.hasCommand && !isOwnEcho) {
            peers += TogetherPeerState(
                uid = author.orEmpty(),
                name = authorName,
                songId = command.songId,
                positionMs = playbackAdvance(command.positionMs, command.playing, command.serverSeq),
                playing = command.playing,
                // The server's sequence is the ordering authority; the local clock plays no part.
                updatedAt = command.serverSeq,
                seq = command.serverSeq,
                avatarUrl = authorAvatar,
            )
        }
        members.forEach { member ->
            if (member.uid == author && !isOwnEcho) return@forEach
            peers += TogetherPeerState(
                uid = member.uid,
                name = member.name,
                songId = 0L,
                positionMs = 0L,
                playing = false,
                updatedAt = 0L,
                seq = 0L,
                avatarUrl = member.avatarUrl,
            )
        }
        return peers.filter { it.uid != excludeUid }
    }

    /**
     * The position a command's playback has reached by now — see [OfficialTogetherRules.playbackAdvance].
     *
     * `progress` is a *snapshot*: the position at the instant the command was issued, not a live value.
     * Left as-is, a peer who has been playing for a minute looks a minute behind, the drift correction
     * seeks to catch up, and every poll repeats it — the desynchronised stutter the whole design is
     * trying to avoid. `serverSeq` doubles as the issue time (it is a millisecond epoch), so the
     * correction is exact rather than a guess.
     */
    private fun playbackAdvance(positionMs: Long, playing: Boolean, serverSeq: Long): Long =
        OfficialTogetherRules.playbackAdvance(
            positionMs = positionMs,
            playing = playing,
            serverSeq = serverSeq,
            nowMs = System.currentTimeMillis(),
        )

    private companion object {
        /** Said in one place, because the invite shape is the likeliest thing to be pasted wrong. */
        const val INCOMPLETE_INVITE = "邀请信息不完整：请粘贴对方分享的邀请"

        /**
         * How often the member list is re-read. Ten seconds: it changes on a join or a leave, and the
         * sheet is the only thing that reads it.
         */
        const val MEMBERS_REFRESH_MS = 10_000L
    }

    /** The signed-in account id, once known; 0 until then (which disables the self-echo filter). */
    private var selfUid: Long = 0L

    /** Members other than this device, refreshed at most every [MEMBERS_REFRESH_MS]. */
    private var members: List<TogetherMember> = emptyList()
    private var membersCheckedAt: Long = 0L

    /**
     * Local aliases for the invite helpers, which live in [OfficialTogetherRules] so their rules are
     * testable without a network.
     */
    private fun buildInvite(roomId: String, inviterId: Long): String =
        OfficialTogetherRules.buildInvite(roomId, inviterId)

    private fun parseInvite(code: String): Pair<String, Long>? =
        OfficialTogetherRules.parseInvite(code)
}
