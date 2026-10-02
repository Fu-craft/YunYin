package com.yunyin.music.data.together

import com.yunyin.music.core.TogetherRemoteState
import com.yunyin.music.core.TogetherUser

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
    private val api: NeteaseTogetherApi,
    /**
     * This device's own account id, for recognising its own commands.
     *
     * The room records the author of its current command, and a device that followed its own echo would
     * fight itself. Read from the signed-in account rather than fetched, because the App already knows it.
     */
    private val ownUid: () -> Long,
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
        // Release whatever room this account already holds, before asking for a new one.
        //
        // **NetEase rooms are scoped to the account, and `room/create` returns the account's existing room
        // rather than making a fresh one** (measured: repeated calls returned the same `roomId` and the same
        // `roomCreateTime`). Combined with a room that was never ended, that means pressing "创建房间" after
        // a previous session hands back the OLD room — still containing the old members. On screen that is
        // indistinguishable from "my other device joined by itself", which is exactly how it was reported.
        endExistingRoom()
        return when (val result = api.create()) {
            is TogetherResult.Ok -> {
                roomId = result.value.roomId
                inviterId = result.value.inviterId
                // A new room is not the old one: clear the "gone" verdict, or the first poll would end it.
                roomGone = false
                enteredAt = System.currentTimeMillis()
                membersCheckedAt = 0L
                heartbeatAt = 0L
                TogetherLog.add("CREATE ok room=${TogetherLog.short(roomId)} inviter=${inviterId}")
                TogetherResult.Ok(result.value.roomId)
            }
            else -> {
                TogetherLog.add("CREATE failed: ${failureMessage(result)}")
                TogetherResult.Failed(failureMessage(result))
            }
        }
    }

    /**
     * Ends the room this account already has, if any.
     *
     * Called before creating, so the new room cannot be an old one. Deliberately best-effort: if the status
     * lookup or the end fails, creating still goes ahead — a stale room is what we are avoiding, not a
     * reason to refuse to create.
     */
    private suspend fun endExistingRoom() {
        val existing = when (val status = api.status()) {
            is TogetherResult.Ok -> status.value.roomId.takeIf { status.value.inRoom && it.isNotBlank() }
            else -> null
        } ?: return
        TogetherLog.add("CREATE clearing the account's existing room ${TogetherLog.short(existing)}")
        api.end(existing)
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
        return when (val result = api.check(parsed.first)) {
            is TogetherResult.Ok ->
                if (result.value) TogetherResult.Ok(emptyList())
                else {
                    TogetherLog.add("CHECK says the room is gone")
                    TogetherResult.NoRoom("这个一起听已结束或失效")
                }
            else -> {
                TogetherLog.add("CHECK failed: ${failureMessage(result)}")
                TogetherResult.Failed(failureMessage(result))
            }
        }
    }

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        val parsed = parseInvite(code) ?: run {
            TogetherLog.add("JOIN aborted: not an invite -> ${TogetherLog.short(code)}")
            return TogetherResult.Failed(INCOMPLETE_INVITE)
        }
        return when (val result = api.accept(parsed.first, parsed.second)) {
            is TogetherResult.Ok -> {
                roomId = parsed.first
                inviterId = parsed.second
                membersCheckedAt = 0L
                heartbeatAt = 0L
                roomGone = false
                enteredAt = System.currentTimeMillis()
                TogetherLog.add("JOIN ok room=${TogetherLog.short(parsed.first)} inviter=${parsed.second}")
                TogetherResult.Ok(Unit)
            }
            // The endpoint answers "ended/expired" as a plain error; naming it helps more than the code.
            else -> {
                TogetherLog.add("JOIN failed: ${failureMessage(result)}")
                TogetherResult.Failed(failureMessage(result))
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
     *
     * The heartbeat is **throttled to every [HEARTBEAT_INTERVAL_MS]**, not sent on every poll. The
     * reference implementation sends one roughly every twelve seconds and skips it on most ticks; this
     * client used to send one every two seconds, which is sixty writes to the room per session against the
     * reference's five. A room's write endpoints are the ones that would be throttled first, and a throttled
     * `play/command` is indistinguishable on screen from "the peer is not following" — so the pacing is
     * aligned rather than merely reduced. The server's own reply says its presence window is 30s
     * (`timeSpan`), so ten seconds is comfortably inside it.
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
        val now = System.currentTimeMillis()
        if (songId > 0L && now - heartbeatAt >= HEARTBEAT_INTERVAL_MS) {
            heartbeatAt = now
            api.heartbeat(room, songId, playing, positionMs)
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
        val command = when (val result = api.snapshot(room)) {
            is TogetherResult.Ok -> result.value
            else -> {
                TogetherLog.add("POLL failed: ${failureMessage(result)}")
                return TogetherResult.Failed(failureMessage(result))
            }
        }
        refreshMembers(room)
        // A room the server says we are no longer in is **gone**, not merely quiet.
        //
        // Without this a device whose partner ended the room sat in it indefinitely: the polls kept
        // succeeding (an empty room reads as an empty command, not an error), so nothing indicated that the
        // session was over. That is the other half of "I created a room and the other device was already
        // in it" — the "already in it" state was a session nobody had ended.
        if (roomGone) {
            TogetherLog.add("POLL room is gone (server says not in room)")
            return TogetherResult.NoRoom("这个一起听已结束")
        }
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
        // The protocol's own names, taken from the reference implementation (qplayer's NetEase plugin):
        // a seek is `PROGRESS`, not `seek`. An unrecognised type is the kind of thing a server accepts
        // with a 200 and then ignores, which is indistinguishable from "the peer is not following".
        val type = when (action) {
            TogetherLocalAction.Goto -> "GOTO"
            TogetherLocalAction.Seek -> "PROGRESS"
            TogetherLocalAction.Play -> "PLAY"
            TogetherLocalAction.Pause -> "PAUSE"
        }
        // `clientSeq` is sent at timestamp magnitude, never from a small counter starting at 0.
        //
        // The reference computes `Math.max(sequence + 1, Date.now())`. A zero or a tiny value is what a
        // server is most likely to discard as "not newer than what I already have", and this client's
        // very first command used to go out as 0. Our own ordering still uses the local Lamport counter
        // passed in as [clientSeq]; this only changes what goes on the wire.
        val wireSeq = maxOf(clientSeq, System.currentTimeMillis())
        return when (val result = api.reportCommand(
            roomId = room, type = type, formerSongId = "0", targetSongId = "$songId",
            progressMs = positionMs, playing = playing, sequence = wireSeq,
        )) {
            is TogetherResult.Ok -> {
                TogetherLog.add("SEND $type song=$songId pos=$positionMs play=$playing seq=$clientSeq")
                TogetherResult.Ok(Unit)
            }
            else -> {
                TogetherLog.add("SEND $type FAILED: ${failureMessage(result)}")
                TogetherResult.Failed(failureMessage(result))
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
        return when (val result = api.reportPlaylist(room, owner, version, queue)) {
            is TogetherResult.Ok -> {
                TogetherLog.add("QUEUE ${queue.size} songs as $owner")
                TogetherResult.Ok(Unit)
            }
            else -> {
                TogetherLog.add("QUEUE failed: ${failureMessage(result)}")
                TogetherResult.Failed(failureMessage(result))
            }
        }
    }

    /**
     * Leaving **does** tell the server, unlike an earlier version of this method.
     *
     * The old behaviour only stopped publishing and let the member time out, on the reasoning that ending
     * the room for the other person would be rude. That reasoning missed the consequence: the account's
     * room then **stays alive**, and because `room/create` hands back the account's existing room, the next
     * "创建房间" reopened the old room with the old members still in it — reported as "my other device
     * joined by itself, I never pressed join". The reference implementation ends the room on leave for this
     * reason; this now matches.
     */
    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        val room = roomId ?: code
        roomId = null
        inviterId = 0L
        members = emptyList()
        membersCheckedAt = 0L
        heartbeatAt = 0L
        roomGone = false
        TogetherLog.add("LEAVE room=${TogetherLog.short(room)}")
        return when (val result = api.end(room)) {
            is TogetherResult.Ok -> TogetherResult.Ok(Unit)
            // Leaving must never fail from the user's point of view: the local session is already cleared,
            // so a server-side error only means the room will expire on its own.
            else -> {
                TogetherLog.add("LEAVE end failed: ${failureMessage(result)}")
                TogetherResult.Ok(Unit)
            }
        }
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> {
        val room = roomId ?: return TogetherResult.Ok(Unit)
        roomId = null
        inviterId = 0L
        members = emptyList()
        membersCheckedAt = 0L
        heartbeatAt = 0L
        roomGone = false
        return when (val result = api.end(room)) {
            is TogetherResult.Ok -> TogetherResult.Ok(Unit)
            else -> TogetherResult.Failed(failureMessage(result))
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
        selfUid = ownUid()
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
        when (val result = api.status()) {
            is TogetherResult.Ok -> {
                // The room this account is in, according to the server. If it says "not in a room" while we
                // believe we are in one, the other side ended it — but not during the first few seconds,
                // when the server may not have caught up with the room we just made or joined.
                val settled = System.currentTimeMillis() - enteredAt > ROOM_SETTLE_MS
                roomGone = settled && (!result.value.inRoom || result.value.roomId.isBlank())
                members = result.value.members
                    .filter { selfUid == 0L || it.uid != selfUid }
                    // The avatar is carried through rather than dropped: `/listentogether/status` returns
                    // `roomUsers[].avatarUrl`, and the room screen shows the two members' faces. Discarding it
                    // here was why the feature had no faces to show.
                    .map { TogetherMember(uid = "${it.uid}", name = it.nickname, avatarUrl = it.avatarUrl) }
            }
            // A failed lookup must not end the session: keep the previous answer and try again next time.
            else -> Unit
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
                // Carried through so the sync rule can act on *what the peer did* (start, stop, seek,
                // change track) instead of inferring it from a position comparison — see [TogetherSync].
                commandType = command.commandType,
                // The room's list, so the follower can play *within* it and keep next/previous working.
                // Only the command's author carries it; the members riding along below are presence only.
                queue = command.queue,
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
         * How long after entering a room a "not in room" answer is ignored.
         *
         * `status` is not guaranteed to be consistent the instant a room is created or joined, and ending
         * the session on that transient would be worse than the problem it solves.
         */
        const val ROOM_SETTLE_MS = 6_000L

        /**
         * How often presence is re-asserted. Ten seconds: the server's own reply reports a 30s window
         * (`timeSpan`), and the reference implementation sends one roughly every twelve.
         */
        const val HEARTBEAT_INTERVAL_MS = 10_000L

        /**
         * How often the member list is re-read. Ten seconds: it changes on a join or a leave, and the
         * sheet is the only thing that reads it.
         */
        const val MEMBERS_REFRESH_MS = 10_000L
    }

    /** The signed-in account id, once known; 0 until then (which disables the self-echo filter). */
    private var selfUid: Long = 0L

    /** When the last heartbeat went out, so presence can be kept without a write on every poll. */
    private var heartbeatAt: Long = 0L

    /**
     * Set when the server reports that this account is no longer in a room.
     *
     * Distinct from a failed request: only an explicit "not in room" means the room is over, and treating a
     * dropped exchange the same way would end a working session on one bad packet.
     */
    private var roomGone: Boolean = false

    /**
     * When this device entered the current room.
     *
     * Used to ignore a "not in room" answer for the first few seconds: `status` is not necessarily
     * consistent the instant a room is created or joined, and acting on it immediately would end a session
     * that had only just started.
     */
    private var enteredAt: Long = 0L

    /** Members other than this device, refreshed at most every [MEMBERS_REFRESH_MS]. */
    private var members: List<TogetherMember> = emptyList()
    private var membersCheckedAt: Long = 0L


    /**
     * The message from a failed call, whatever the outcome type.
     *
     * Needed because the `when`s here switch on [TogetherResult] from the API, where only `Ok` and `Failed`
     * occur, so the failure branch is `else ->` and the compiler can no longer prove the result is a
     * `Failed`. Reading `.message` through this keeps those branches one line each without re-adding
     * outcome cases the API cannot produce.
     */
    private fun failureMessage(result: TogetherResult<*>): String = when (result) {
        is TogetherResult.Failed -> result.message
        is TogetherResult.NoRoom -> result.message
        is TogetherResult.RateLimited -> result.message
        is TogetherResult.Ok -> "一起听请求失败"
    }

    /**
     * Local aliases for the invite helpers, which live in [OfficialTogetherRules] so their rules are
     * testable without a network.
     */
    private fun buildInvite(roomId: String, inviterId: Long): String =
        OfficialTogetherRules.buildInvite(roomId, inviterId)

    private fun parseInvite(code: String): Pair<String, Long>? =
        OfficialTogetherRules.parseInvite(code)
}
