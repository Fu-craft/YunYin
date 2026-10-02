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

        /** Server-side ordering. A large epoch-like start, matching the real `serverSeq`. */
        var seq = 1_700_000_000_000L

        var publishes = 0
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
    private class Device(val uid: String, private val room: FakeRoom, scope: CoroutineScope) {
        val session = TogetherSession(FakeTransport(uid, room), scope)
        val applied = mutableListOf<SyncAction>()

        var songId = 0L
        var positionMs = 0L
        var playing = false

        init {
            session.localPlayback = {
                if (songId > 0L) LocalPlayback(songId, positionMs, playing) else null
            }
            session.applyAction = { action ->
                applied += action
                // Apply it, as the real host does through the player, so the loop can converge.
                when (action) {
                    is SyncAction.PlaySong -> {
                        songId = action.songId
                        positionMs = action.positionMs
                        playing = true
                    }
                    is SyncAction.Seek -> positionMs = action.positionMs
                    SyncAction.Play -> playing = true
                    SyncAction.Pause -> playing = false
                    SyncAction.None -> Unit
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
}
