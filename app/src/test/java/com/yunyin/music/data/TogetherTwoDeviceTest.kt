package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherLocalAction
import com.yunyin.music.data.together.TogetherMember
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherResult
import com.yunyin.music.data.together.TogetherSession
import com.yunyin.music.data.together.TogetherTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two devices, one room, no server and no account.
 *
 * ## Why this exists
 *
 * Together-listen cannot be exercised end to end with a single NetEase account: its routes answer
 * `301 需要登录` to a guest session (measured with `tools/probe_anon_together.py`), so a real room needs two
 * real accounts. That leaves the most failure-prone part of the feature — two clients disagreeing about what
 * is playing — untestable on hardware, which is exactly the part that has broken repeatedly ("加入房间后无法
 * 切歌", "两边互相纠偏来回震").
 *
 * The client does not know or care which transport it is on, so both devices can be run against a fake room
 * that models the *official* semantics: a single room-wide command, a monotonically increasing server
 * sequence, and — critically — **a device never sees its own command** (`sync/playlist/get` reports the peer,
 * measured). With that, the two-device behaviour can be proven without a network at all.
 *
 * What is asserted is behaviour, not implementation: B adopts A's song; A follows B's pause; and once the
 * room has settled, neither side keeps issuing corrections (the ping-pong failure).
 */
class TogetherTwoDeviceTest {

    /** The room both devices share, as the server would hold it. */
    private class FakeRoom {
        val members = linkedSetOf<String>()

        /** The one current command: who issued it and what it says. */
        var author: String? = null
        var songId = 0L
        var positionMs = 0L
        var playing = false

        /**
         * The kind of the current command, as the official protocol reports it.
         *
         * Modelled because it is load-bearing: the room does not just say "this is my position", it says
         * *what happened* (`GOTO`, `PLAY`, `PAUSE`, `PROGRESS`), and the receiver acts on that. A fake that
         * dropped it would silently test the legacy comparison instead of the real rule.
         */
        var commandType = ""

        /** Server-side ordering. A large epoch-like start, matching the real `serverSeq`. */
        var seq = 1_700_000_000_000L

        var publishes = 0

        /**
         * Every command actually sent, as `(author, type)`.
         *
         * Recorded so a test can assert on what a device *broadcast*, not only on what it applied. The echo
         * bug — following a pause and announcing the opposite — is invisible in `applied` and only visible
         * here.
         */
        val published = mutableListOf<Pair<String, String>>()
    }

    /**
     * One device's view of [room].
     *
     * Each device gets its own instance so the transport knows which member it is — `publishAction` carries
     * no uid, because on the real transport the identity comes from the request's cookie.
     */
    private class FakeTransport(
        private val me: String,
        private val room: FakeRoom,
    ) : TogetherTransport {
        override val configured = true

        /** Short, so the poll loop turns over quickly in a test. */
        override val pollIntervalMs = 5L

        override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
            room.members += me
            return TogetherResult.Ok("ROOM")
        }

        override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
            TogetherResult.Ok(room.members.map { TogetherMember(it, it) })

        override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
            room.members += me
            return TogetherResult.Ok(Unit)
        }

        override suspend fun postState(
            code: String,
            uid: String,
            name: String,
            songId: Long,
            positionMs: Long,
            playing: Boolean,
            seq: Long,
        ): TogetherResult<List<TogetherPeerState>> = pollPeers(code, uid)

        override suspend fun pollPeers(
            code: String,
            excludeUid: String,
        ): TogetherResult<List<TogetherPeerState>> {
            // Only other members, and only the author carries the command -- so a device never reads its own
            // back. This is the property the real endpoint has and the one that stops self-following.
            val peers = room.members.filter { it != me }.map { other ->
                if (other == room.author && room.songId > 0L) {
                    TogetherPeerState(
                        uid = other,
                        name = other,
                        songId = room.songId,
                        positionMs = room.positionMs,
                        playing = room.playing,
                        updatedAt = room.seq,
                        seq = room.seq,
                        commandType = room.commandType,
                    )
                } else {
                    TogetherPeerState(other, other, 0L, 0L, false, 0L, 0L)
                }
            }
            return TogetherResult.Ok(peers)
        }

        override suspend fun publishAction(
            action: TogetherLocalAction,
            songId: Long,
            positionMs: Long,
            playing: Boolean,
            clientSeq: Long,
        ): TogetherResult<Unit> {
            room.author = me
            room.songId = songId
            room.positionMs = positionMs
            room.playing = playing
            // The wire type, as the real transport derives it from the action.
            room.commandType = when (action) {
                TogetherLocalAction.Goto -> "GOTO"
                TogetherLocalAction.Seek -> "PROGRESS"
                TogetherLocalAction.Play -> "PLAY"
                TogetherLocalAction.Pause -> "PAUSE"
            }
            room.published += me to room.commandType
            room.seq += 1
            room.publishes++
            return TogetherResult.Ok(Unit)
        }

        override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
            room.members -= me
            return TogetherResult.Ok(Unit)
        }

        override suspend fun closeRoom(code: String): TogetherResult<Unit> = TogetherResult.Ok(Unit)
    }

    /** A device: its own playback, plus what the room has told it to do. */
    private class Device(
        val uid: String,
        private val room: FakeRoom,
        private val scope: CoroutineScope,
    ) {
        val session = TogetherSession(FakeTransport(uid, room), scope)
        val applied = mutableListOf<SyncAction>()

        var songId = 0L
        var positionMs = 0L
        var playing = false

        /**
         * Whether this device's player is between tracks (loading/buffering).
         *
         * Real players report this for a second or two after `next()`, and the session is required not to
         * publish during it. Exposed so a test can hold the room in that state and prove a claimed track
         * change still goes out afterwards.
         */
        var transitioning = false

        /**
         * How long the player takes to actually obey a correction.
         *
         * A real media controller applies play/pause asynchronously, so for a moment after following a
         * correction the device still reports its *old* state. That lag is what produced the echo the sync
         * engine has to suppress, so the fake reproduces it rather than applying instantly.
         */
        var applyLatencyMs = 0L

        init {
            session.localPlayback = {
                if (songId > 0L) LocalPlayback(songId, positionMs, playing) else null
            }
            session.localTransitioning = { transitioning }
            session.applyAction = { action ->
                applied += action
                // Apply it, as the real host does through the player, so the loop can converge.
                val apply = {
                    when (action) {
                        is SyncAction.PlaySong -> {
                            songId = action.songId
                            positionMs = action.positionMs
                            playing = action.playing
                        }
                        is SyncAction.Seek -> positionMs = action.positionMs
                        SyncAction.Play -> playing = true
                        SyncAction.Pause -> playing = false
                        SyncAction.None -> Unit
                    }
                }
                if (applyLatencyMs > 0L) {
                    scope.launch {
                        delay(applyLatencyMs)
                        apply()
                    }
                } else {
                    apply()
                }
            }
        }

        fun play(id: Long, atMs: Long) {
            songId = id
            positionMs = atMs
            playing = true
            session.noteUserAction(TogetherLocalAction.Goto)
        }

        fun pause() {
            playing = false
            session.noteUserAction(TogetherLocalAction.Pause)
        }

        /**
         * Presses play the way the real UI does, and models the player taking a moment to obey.
         *
         * `isPlaying` stays false for [startLatencyMs] — which is what a buffering player reports — so any
         * code that published a predicted "play" would be publishing against an unchanged state.
         */
        fun pressPlay(startLatencyMs: Long = 0L) {
            session.noteUserAction(TogetherLocalAction.Play)
            if (startLatencyMs > 0L) {
                scope.launch {
                    delay(startLatencyMs)
                    playing = true
                }
            } else {
                playing = true
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (condition()) return true
            delay(10)
            waited += 10
        }
        return condition()
    }

    @Test
    fun `a joiner adopts the host's track, and the host follows the joiner's pause`() = runBlocking {
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            // 1. The host opens a room and the guest joins it.
            host.session.createRoom(uid = "host", name = "房主")
            assertTrue("host should be in a room", waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue("guest should be in a room", waitUntil { guest.session.state.value.inRoom })

            // 2. The host starts a song. This is the "加入房间后能跟随对方" case.
            host.play(1330348068L, atMs = 42_000L)
            assertTrue(
                "the guest should adopt the host's song",
                waitUntil { guest.songId == 1330348068L },
            )
            assertEquals("and start it at the host's position", 42_000L, guest.positionMs)
            assertTrue("the guest should be playing", guest.playing)

            // 3. The guest pauses. The room belongs to whoever acted last, so the host must follow —
            //    this is the "加入房间后无法切歌" direction, which used to fail.
            guest.pause()
            assertTrue(
                "the host should follow the guest's pause",
                waitUntil { host.applied.any { it is SyncAction.Pause } },
            )
            assertTrue("the host should end up paused", !host.playing)

            // 4. Neither side may keep correcting the other once it has settled (the ping-pong failure).
            val hostSettled = host.applied.size
            val guestSettled = guest.applied.size
            delay(400)
            assertEquals("the host must stop re-correcting", hostSettled, host.applied.size)
            assertEquals("the guest must stop re-correcting", guestSettled, guest.applied.size)
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `a pause is followed even when the two positions have drifted apart`() = runBlocking {
        // The reported inversion, as a two-device test. When only positions were compared, a mismatch was
        // read as "seek" — and a seek moves the needle without ever stopping the music. So the receiving
        // device kept playing while the sender sat paused, and the mirror image happened on a resume. The
        // command's own kind (`PAUSE`) is what has to decide, not the gap between the two positions.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            host.play(111L, atMs = 0L)
            assertTrue(waitUntil { guest.songId == 111L })

            // Deliberately far apart — ten seconds, five times the drift tolerance.
            guest.positionMs = 10_000L
            guest.pause()

            assertTrue(
                "the host must follow the pause, not seek: ${host.applied}",
                waitUntil { host.applied.any { it is SyncAction.Pause } },
            )
            assertTrue("the host must end up paused", !host.playing)
            assertTrue(
                "a pause must never arrive as a seek: ${host.applied}",
                host.applied.none { it is SyncAction.Seek },
            )
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `a next pressed while the player is loading still reaches the peer`() = runBlocking {
        // The second half of "他那边无法进行上一首下一首的操作". A next/previous is claimed the instant it is
        // tapped, but the player then reports "between tracks" for a second or two. The claim used to be
        // discarded on those ticks, and because the baseline had also moved onto the new track there was
        // nothing left for the unannounced-change rule to notice — so the track changed locally and the peer
        // was never told. Keeping the claim means it goes out as soon as the player settles.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            host.play(111L, atMs = 0L)
            assertTrue(waitUntil { guest.songId == 111L })

            // The host taps next: the player is now on 222 but loading, which is what the session must not
            // publish. Several ticks pass in that state.
            host.transitioning = true
            host.play(222L, atMs = 0L)
            delay(250)

            // The player settles.
            host.transitioning = false

            assertTrue(
                "the peer must adopt the track the host switched to: guest=${guest.songId}",
                waitUntil { guest.songId == 222L },
            )
            assertEquals("and since it was a start command, playing", true, guest.playing)
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `following a pause is never echoed back as a play`() = runBlocking {
        // The other half of the inversion, and the one a single device cannot show: a correction is applied
        // asynchronously, so for a moment the receiving player still reports its *old* state. With the
        // baseline already moved onto the target, that stale reading looks like a fresh local change — and
        // the device announces the opposite of what it just did. The two then take turns, each appearing to
        // do the reverse of the other ("我暂停他那边就播放").
        //
        // The window is asserted from the *wire*: what this device broadcast, not what it applied.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            host.play(111L, atMs = 0L)
            assertTrue(waitUntil { guest.songId == 111L })
            assertTrue(waitUntil { guest.playing })

            // From here on, the host's player takes a moment to obey — as a real one does.
            host.applyLatencyMs = 250L
            room.published.clear()

            // The guest pauses. The host must follow, and must not broadcast a PLAY while its player is
            // still catching up.
            guest.pause()
            assertTrue(
                "the host should follow the pause",
                waitUntil { host.applied.any { it is SyncAction.Pause } },
            )
            delay(400)

            val hostPlay = room.published.filter { it.first == "host" && it.second == "PLAY" }
            assertTrue(
                "following a pause must not be echoed back as a play: ${room.published}",
                hostPlay.isEmpty(),
            )
            assertTrue("the host must end up paused", !host.playing)
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `pressing play while the player is still buffering announces PLAY, never PAUSE`() = runBlocking {
        // The reported bug, at its source. The play button is claimed the instant it is pressed, but the
        // player reports `isPlaying == false` until it has something to play. Any code that sent the claim
        // (or "reconciled" it against the player) therefore broadcast a **pause** for a press of play, and
        // the peer obeyed — "我这边点播放他那边就暂停". The rule must wait for the state to actually change.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            host.play(111L, atMs = 0L)
            assertTrue(waitUntil { guest.songId == 111L })
            assertTrue(waitUntil { guest.playing })

            host.pause()
            assertTrue("the guest should follow the pause", waitUntil { !guest.playing })
            room.published.clear()

            // Press play; the player takes a moment (buffering) before it reports playing.
            host.pressPlay(startLatencyMs = 300L)
            delay(150)
            assertTrue(
                "a press of play must never be announced as a pause: ${room.published}",
                room.published.none { it.first == "host" && it.second == "PAUSE" },
            )

            // Once the player is actually playing, the PLAY goes out and the guest follows it.
            assertTrue(
                "the peer should end up playing",
                waitUntil { guest.playing },
            )
            assertTrue(
                "a PLAY must have been broadcast: ${room.published}",
                room.published.any { it.first == "host" && it.second == "PLAY" },
            )
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `a device never follows its own command`() = runBlocking {
        val room = FakeRoom()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val solo = Device("solo", room, scope)
            solo.session.createRoom(uid = "solo", name = "我")
            assertTrue(waitUntil { solo.session.state.value.inRoom })

            // Play something on its own. With no peer present, nothing should ever be "corrected" back —
            // if a device could read its own command, this would immediately try to re-apply it.
            solo.play(1330348068L, atMs = 1_000L)
            delay(400)

            assertTrue(
                "a lone device must not receive corrections of its own state: ${solo.applied}",
                solo.applied.isEmpty(),
            )
            assertEquals("and its playback is untouched", 1330348068L, solo.songId)
            assertTrue(solo.playing)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `adopting the host's song does not push the joiner's old song back at the host`() = runBlocking {
        // The regression behind "切歌不跟随" / "成员切不了歌": the session announced its local state *after*
        // applying the room's, using the playback captured before the apply — so a joiner that had just
        // adopted the host's track immediately announced the track it was abandoning, overwriting the room's
        // command. The host then followed that, and the two devices sat on each other's song forever.
        //
        // Asserted as: the host keeps ITS song, and is never dragged onto the joiner's.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            host.play(222L, atMs = 0L)
            guest.play(111L, atMs = 0L)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            // Give both loops several ticks to settle.
            assertTrue("the two should agree on the host's song", waitUntil { guest.songId == 222L })
            delay(600)

            assertEquals("the host must keep its own song", 222L, host.songId)
            assertEquals("and so must the guest", 222L, guest.songId)
            assertTrue(
                "the host must never be moved onto the joiner's track: host=${host.songId}",
                host.songId != 111L,
            )
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }

    @Test
    fun `two devices already playing different songs converge on one of them`() = runBlocking {
        // The realistic case: both people open the app, each is already playing something, and only THEN does
        // one create the room and the other join. Neither starts from silence, so they must still end up on
        // one song rather than each keeping its own.
        val room = FakeRoom()
        val hostScope = CoroutineScope(Dispatchers.Default)
        val guestScope = CoroutineScope(Dispatchers.Default)
        try {
            val host = Device("host", room, hostScope)
            val guest = Device("guest", room, guestScope)

            // Both are mid-song before the room exists.
            host.play(111L, atMs = 5_000L)
            guest.play(222L, atMs = 9_000L)

            host.session.createRoom(uid = "host", name = "房主")
            assertTrue(waitUntil { host.session.state.value.inRoom })
            guest.session.joinRoom(code = "ROOM", uid = "guest", name = "对方")
            assertTrue(waitUntil { guest.session.state.value.inRoom })

            // They must agree on a single song — either one is acceptable, disagreement is not.
            val converged = waitUntil(4_000) { host.songId == guest.songId }
            assertTrue(
                "the two devices must end on the same song, got host=${host.songId} guest=${guest.songId}",
                converged,
            )
            assertTrue(
                "someone must actually have followed: host=${host.applied} guest=${guest.applied}",
                host.applied.isNotEmpty() || guest.applied.isNotEmpty(),
            )

            // And then stay settled rather than trading the song back and forth.
            val hostApplied = host.applied.size
            val guestApplied = guest.applied.size
            delay(500)
            assertEquals("the host must stop re-correcting", hostApplied, host.applied.size)
            assertEquals("the guest must stop re-correcting", guestApplied, guest.applied.size)
        } finally {
            hostScope.cancel()
            guestScope.cancel()
        }
    }
}
