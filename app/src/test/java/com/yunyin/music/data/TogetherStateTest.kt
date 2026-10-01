package com.yunyin.music.data

import com.yunyin.music.data.together.TogetherMember
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherUiState
import com.yunyin.music.data.together.noticesCleared
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room state's two different kinds of "clear".
 *
 * This exists because the two were conflated once and it was not a cosmetic bug: closing the panel
 * rebuilt the whole state, so the room code was discarded. The UI then offered "create a room" again
 * while the polling loop had already stopped on the now-null code — meaning closing a panel silently
 * ended the session and never told the peer. The behaviour looked like "the room didn't stick"; the
 * actual defect was that a *notice* and a *session* were being cleared by the same function.
 */
class TogetherStateTest {

    private val room = TogetherUiState(
        configured = true,
        code = "ABCD2345WXYZ",
        isHost = true,
        members = listOf(TogetherMember("me", "我")),
        peers = listOf(
            TogetherPeerState(
                uid = "peer", name = "对方", songId = 42L, positionMs = 1000L,
                playing = true, updatedAt = 5L, seq = 1L,
            ),
        ),
        ended = "房间已结束",
        error = "网络错误",
    )

    @Test
    fun `clearing notices keeps the room`() {
        val after = room.noticesCleared()
        assertEquals("the room code must survive closing the panel", room.code, after.code)
        assertEquals(room.isHost, after.isHost)
        assertEquals(room.members, after.members)
        assertEquals(room.peers, after.peers)
        assertTrue("still in the room", after.inRoom)
    }

    @Test
    fun `clearing notices does clear the notices`() {
        val after = room.noticesCleared()
        assertNull(after.ended)
        assertNull(after.error)
    }

    @Test
    fun `an ended room counts as not in a room, so the notice is shown`() {
        // `inRoom` is false while a notice is up, which is what makes the sheet explain why rather
        // than presenting the room as if nothing happened.
        val ended = TogetherUiState(configured = true, code = "ABCD2345WXYZ", ended = "房间不存在")
        assertTrue(!ended.inRoom)
    }

    @Test
    fun `a plain room is in a room`() {
        val plain = TogetherUiState(configured = true, code = "ABCD2345WXYZ")
        assertTrue(plain.inRoom)
    }

    @Test
    fun `no room is not in a room`() {
        assertTrue(!TogetherUiState(configured = true).inRoom)
    }
}
