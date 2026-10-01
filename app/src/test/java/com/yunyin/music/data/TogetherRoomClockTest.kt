package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room's logical clock is scoped to **one room**.
 *
 * This is the bug that made joining a room do nothing: the counter lived on the session object, which
 * lives for the whole app, so it carried over between rooms. A peer starting a fresh room reports
 * `seq = 1`, while our leftover counter could already be 7 — and because a higher counter means "acted
 * later", our device concluded **its own** state was newer and refused to follow. The other member's
 * song never loaded, which reads as a sync failure but is really a units error: comparing counters
 * from two different conversations.
 *
 * There is no way to unit-test `resetClock()`'s call sites without a transport, so the *rule* is pinned
 * here instead — a fresh room starts from zero on both sides, which is what makes the first exchange
 * comparable.
 */
class TogetherRoomClockTest {

    private fun peer(songId: Long, positionMs: Long, seq: Long, playing: Boolean = true) =
        TogetherPeerState(
            uid = "peer", name = "对方", songId = songId, positionMs = positionMs,
            playing = playing, updatedAt = 0L, seq = seq,
        )

    @Test
    fun `both sides starting at zero means the first report is followed`() {
        // The state a joined room is actually in: neither side has acted, so the peer's first published
        // song must be adopted. With a leftover counter this returned None — the reported bug.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 0L, positionMs = 0L, playing = false),
            peer = peer(songId = 222L, positionMs = 30_000L, seq = 1L),
            lastAppliedAt = 0L,
            now = 10_000L,
            localSeq = 0L,
        )
        assertEquals(SyncAction.PlaySong(222L, 30_000L), action)
    }

    @Test
    fun `a leftover counter would have suppressed it`() {
        // The old behaviour, asserted explicitly so the failure mode is on record: a counter from a
        // previous room (7) makes an incoming seq of 1 look stale, and the peer is ignored.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 0L, positionMs = 0L, playing = false),
            peer = peer(songId = 222L, positionMs = 30_000L, seq = 1L),
            lastAppliedAt = 0L,
            now = 10_000L,
            localSeq = 7L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a fresh room's counters are comparable on both sides`() {
        // Whatever each side does first, the counters stay in the same small range — the property that
        // makes "higher means later" meaningful. Cross-room accumulation breaks exactly this.
        var mine = 0L
        var theirs = 0L
        repeat(3) {
            mine = TogetherSync.nextSeq(mine, theirs)
            theirs = TogetherSync.nextSeq(theirs, mine)
        }
        assertTrue("counters drifted apart: mine=$mine theirs=$theirs", kotlin.math.abs(mine - theirs) <= 2)
    }
}
