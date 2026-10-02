package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ordering across *rooms* — the bug class that the local counter caused.
 *
 * The session object lives for the whole app, so anything it remembers between rooms is a hazard. An earlier
 * design kept a Lamport counter there and compared it against the peer's server sequence; because the two
 * numbers came from different clocks, a value carried over (or simply advanced past the server's own
 * numbering) made later commands compare as "older" and be ignored. The reported shape was precise:
 * **the first song syncs, the host switches, and nothing follows.**
 *
 * The rule that replaced it has no cross-room state to leak: a command is judged by *what it is* (author,
 * song, play state), so the same command in a new room is followed exactly as the first one in an old room.
 * These tests pin that property rather than the deleted counter.
 */
class TogetherRoomClockTest {

    private fun peer(songId: Long, positionMs: Long, seq: Long, playing: Boolean = true) =
        TogetherPeerState(
            uid = "peer", name = "对方", songId = songId, positionMs = positionMs,
            playing = playing, updatedAt = 0L, seq = seq,
        )

    @Test
    fun `the first command of a room is followed even with a huge server sequence`() {
        // A fresh room must adopt the peer's song regardless of how the server numbers its commands — the
        // ordering rule no longer looks at that number at all.
        listOf(0L, 1L, 999L, 1_790_930_000_000L).forEach { seq ->
            val action = TogetherSync.decide(
                local = LocalPlayback(songId = 0L, positionMs = 0L, playing = false),
                peer = peer(songId = 222L, positionMs = 30_000L, seq = seq),
                peerIsNew = true,
                lastAppliedAt = 0L,
                now = 10_000L,
            )
            assertEquals("seq=$seq", SyncAction.PlaySong(222L, 30_000L), action)
        }
    }

    @Test
    fun `a later command in the same room is followed just as readily`() {
        // The reported bug, as a property: nothing about having followed an earlier command can make the
        // next one look stale.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 222L, positionMs = 12_000L, playing = true),
            peer = peer(songId = 333L, positionMs = 0L, seq = 1_790_930_000_001L),
            peerIsNew = true,
            lastAppliedAt = 10_000L,
            now = 10_500L,
        )
        assertEquals(SyncAction.PlaySong(333L, 0L), action)
    }

    @Test
    fun `identity is the only thing that decides newness, and it is stable across rooms`() {
        // No room id, no sequence, no counter takes part: the same command looks identical in any room, and a
        // changed command looks different in every room.
        val a = peer(songId = 222L, positionMs = 1_000L, seq = 1L)
        val sameLater = peer(songId = 222L, positionMs = 90_000L, seq = 77L)
        val changed = peer(songId = 333L, positionMs = 1_000L, seq = 1L)

        assertEquals(TogetherSync.peerIdentity(a), TogetherSync.peerIdentity(sameLater))
        assertNotEquals(TogetherSync.peerIdentity(a), TogetherSync.peerIdentity(changed))
    }
}
