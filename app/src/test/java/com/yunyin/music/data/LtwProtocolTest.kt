package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherSync
import com.yunyin.music.data.together.ltw.LtwJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LTW protocol shapes and the server-authoritative convergence rule.
 *
 * The JSON fixtures are copied from responses the **deployed service actually returned** while this transport
 * was built (`GET /api/rooms/:id/state`, and a `room_state_updated` broadcast), so a field rename on the
 * service side shows up here as a failing parse rather than as a room that silently does nothing.
 */
class LtwProtocolTest {

    /** A real `/state` body, trimmed to the fields the client reads. */
    private val stateJson = JSONObject(
        """
        {
          "roomId": "EURMZG",
          "version": 4,
          "controllerUserUuid": "11111111-1111-4111-8111-111111111111",
          "settings": {"allowMemberControl": true, "autoPauseOnMemberChange": true},
          "members": [
            {"userUuid": "11111111-1111-4111-8111-111111111111", "nickname": "房主", "role": "controller"},
            {"userUuid": "22222222-2222-4222-8222-222222222222", "nickname": "听众", "role": "listener"}
          ],
          "queue": [
            {"stableKey": "netease:1001", "channelId": "netease", "audioId": "1001",
             "name": "第一首", "artist": "歌手", "durationMs": 200000},
            {"stableKey": "netease:1002", "channelId": "netease", "audioId": "1002",
             "name": "第二首", "artist": "歌手", "durationMs": 210000}
          ],
          "currentIndex": 1,
          "track": {"stableKey": "netease:1002", "channelId": "netease", "audioId": "1002",
                    "name": "第二首", "artist": "歌手", "durationMs": 210000},
          "playback": {"state": "playing", "basePositionMs": 7184, "baseTimestampMs": 1791693967241,
                       "playbackRate": 1, "repeatMode": 0, "shuffleEnabled": false},
          "roomStatus": "active"
        }
        """.trimIndent(),
    )

    @Test
    fun `a real room state parses into the client's shape`() {
        val state = LtwJson.roomState(stateJson)
        assertNotNull("the state must parse", state)
        requireNotNull(state)

        assertEquals("EURMZG", state.roomId)
        assertEquals(4L, state.version)
        assertEquals(2, state.members.size)
        assertEquals("房主", state.members.first().nickname)
        assertEquals("controller", state.members.first().role)
        assertEquals(2, state.queue.size)
        assertEquals("第二首", state.track?.name)
        assertEquals(1, state.currentIndex)
        assertTrue("the playback anchor is read", state.playback?.playing == true)
        assertEquals(7_184L, state.playback?.basePositionMs)
        assertFalse(state.closed)
        assertFalse(state.controllerOffline)
    }

    @Test
    fun `a member without a uuid is dropped rather than kept as an empty identity`() {
        val json = JSONObject(stateJson.toString())
        json.put(
            "members",
            org.json.JSONArray()
                .put(JSONObject().put("nickname", "没有 id").put("role", "listener"))
                .put(JSONObject().put("userUuid", "abc").put("nickname", "有 id").put("role", "listener")),
        )
        val state = LtwJson.roomState(json)
        assertEquals("only the member with an identity survives", 1, state?.members?.size)
        assertEquals("有 id", state?.members?.first()?.nickname)
    }

    @Test
    fun `a track with no audio id is not usable as a song`() {
        val json = JSONObject(stateJson.toString())
        json.put(
            "queue",
            org.json.JSONArray().put(
                JSONObject().put("stableKey", "x").put("channelId", "netease"), // no audioId
            ),
        )
        assertEquals("a track without an id is skipped", 0, LtwJson.roomState(json)?.queue?.size)
    }

    @Test
    fun `an empty object is not a room`() {
        // The service answers session-less reads with `{}`-shaped data; treating that as a room would make
        // the sheet claim a room that does not exist.
        assertNull(LtwJson.roomState(JSONObject("{}")))
        assertNull(LtwJson.roomState(null))
    }

    // ---------------------------------------------------------------- authoritative convergence

    private fun server(songId: Long, positionMs: Long, playing: Boolean) = TogetherPeerState(
        uid = "controller-uuid",
        name = "房主",
        songId = songId,
        positionMs = positionMs,
        playing = playing,
        updatedAt = 1L,
        seq = 1L,
        // The authoritative marker the transport uses so the rule never tries to interpret a "kind".
        commandType = "AUTHORITATIVE",
    )

    @Test
    fun `a device that already agrees with the server is left alone`() {
        // The whole point of a server-authoritative room: agreement costs nothing, so "reconcile every poll"
        // does not mean "correct every poll".
        assertEquals(
            SyncAction.None,
            TogetherSync.reconcile(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                server(songId = 200L, positionMs = 30_500L, playing = true),
            ),
        )
    }

    @Test
    fun `a paused server pauses the device, and a playing server starts it`() {
        // This is the behaviour the reported inversion was about, now with a rule that cannot invert: the
        // server's state is the only input, so the device cannot report the opposite of what it did.
        assertEquals(
            SyncAction.Pause,
            TogetherSync.reconcile(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                server(songId = 200L, positionMs = 30_000L, playing = false),
            ),
        )
        assertEquals(
            SyncAction.Play,
            TogetherSync.reconcile(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = false),
                server(songId = 200L, positionMs = 30_000L, playing = true),
            ),
        )
    }

    @Test
    fun `a different song on the server is adopted with its position and play state`() {
        assertEquals(
            SyncAction.PlaySong(300L, 0L, playing = false),
            TogetherSync.reconcile(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                server(songId = 300L, positionMs = 0L, playing = false),
            ),
        )
    }

    @Test
    fun `a position drift beyond the tolerance is seeked`() {
        assertEquals(
            SyncAction.Seek(200L, 45_000L),
            TogetherSync.reconcile(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                server(songId = 200L, positionMs = 45_000L, playing = true),
            ),
        )
    }
}
