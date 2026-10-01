package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listen-together sync rule.
 *
 * The cases here are the ways two devices correcting each other go wrong — the failure this feature
 * is most likely to ship with, because it looks like it works when you test it alone:
 *
 *  - the ping-pong: A seeks, B seeks back, forever (guarded by the settle window);
 *  - correcting a sub-second skew, which is inaudible as desync but very audible as a stutter;
 *  - fighting over the play/pause state while the track is already right;
 *  - reacting to a member who has joined but is not playing anything yet.
 */
class TogetherSyncTest {

    private fun peer(
        songId: Long,
        positionMs: Long,
        playing: Boolean = true,
        uid: String = "peer",
        seq: Long = 1,
    ) = TogetherPeerState(
        uid = uid,
        name = "听众",
        songId = songId,
        positionMs = positionMs,
        playing = playing,
        updatedAt = 0L,
        seq = seq,
    )

    private val local = LocalPlayback(songId = 100L, positionMs = 0L, playing = false)

    @Test
    fun `no peer means nothing to do`() {
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(local, null, lastAppliedAt = 0L, now = 10_000L),
        )
    }

    @Test
    fun `a peer who has not started a song is not followed`() {
        // A member who joined the room but has no track yet: following it would stop the music for
        // everyone, which is the opposite of what they asked for.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(local, peer(songId = 0L, positionMs = 0L), 0L, now = 10_000L),
        )
    }

    @Test
    fun `a different song is adopted with the peer's position`() {
        val action = TogetherSync.decide(
            local,
            peer(songId = 200L, positionMs = 30_000L),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.PlaySong(200L, 30_000L), action)
    }

    @Test
    fun `a drift past the tolerance is corrected by seeking`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 34_000L),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Seek(200L, 34_000L), action)
    }

    @Test
    fun `a drift inside the tolerance is left alone`() {
        // 1.5s apart: inaudible as desync, so seeking back would only add a stutter.
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 31_500L),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a drift exactly at the tolerance is not corrected`() {
        // Boundary: the test is `> tolerance`, so exactly at it stays put. Pinned because an
        // off-by-one here turns every steady state into a seek loop.
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 32_000L),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a negative drift (peer behind) is also corrected`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 34_000L, playing = true),
            peer(songId = 200L, positionMs = 30_000L),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Seek(200L, 30_000L), action)
    }

    @Test
    fun `same song and position but the peer is paused pauses us`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 30_000L, playing = false),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Pause, action)
    }

    @Test
    fun `same song and position but the peer is playing starts us`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = false),
            peer(songId = 200L, positionMs = 30_000L, playing = true),
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Play, action)
    }

    @Test
    fun `a correct play state is left alone`() {
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                peer(songId = 200L, positionMs = 30_500L, playing = true),
                lastAppliedAt = 0L,
                now = 10_000L,
            ),
        )
    }

    @Test
    fun `drift correction is suppressed while a correction is still settling`() {
        // The ping-pong guard: right after we applied the peer's state, a re-reported difference must
        // be ignored rather than chased, or the two devices correct each other forever. Equal sequence
        // numbers mean neither side has *acted* — so this is drift, and the window applies. (A peer
        // action is a different case and is applied immediately; see the who-wins tests above.)
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 999L, positionMs = 0L, seq = 4L),
            lastAppliedAt = 9_000L,
            now = 10_000L,
            localSeq = 4L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `drift correction resumes once the settle window has passed`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 999L, positionMs = 0L, seq = 4L),
            lastAppliedAt = 1_000L,
            now = 10_000L,
            localSeq = 4L,
            settleMs = 6_000L,
        )
        assertEquals(SyncAction.PlaySong(999L, 0L), action)
    }

    // ---------------------------------------------------------------- who wins

    @Test
    fun `a local action taken after the peer's report is not overwritten`() {
        // THE reported bug: you press next, the peer's (older) state still says the previous track, and
        // the room pulls you back — the button looks dead. Our counter has moved past theirs, so the
        // peer's track must be ignored entirely.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
            peer = peer(songId = 200L, positionMs = 90_000L, seq = 3L),
            lastAppliedAt = 0L,
            now = 10_000L,
            localSeq = 4L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a local pause is not undone by the peer still playing`() {
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 200L, positionMs = 30_000L, playing = false),
            peer = peer(songId = 200L, positionMs = 30_000L, playing = true, seq = 2L),
            lastAppliedAt = 0L,
            now = 10_000L,
            localSeq = 5L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a newer peer action wins over ours`() {
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
            peer = peer(songId = 200L, positionMs = 90_000L, seq = 7L),
            lastAppliedAt = 0L,
            now = 10_000L,
            localSeq = 6L,
        )
        assertEquals(SyncAction.PlaySong(200L, 90_000L), action)
    }

    @Test
    fun `a peer action is applied even inside the settle window`() {
        // An explicit action is not drift, so the anti-ping-pong window must not suppress it: the peer
        // pressed next and expects the room to move now.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
            peer = peer(songId = 200L, positionMs = 0L, seq = 9L),
            lastAppliedAt = 9_900L,
            now = 10_000L,
            localSeq = 8L,
        )
        assertEquals(SyncAction.PlaySong(200L, 0L), action)
    }

    @Test
    fun `equal sequences are treated as drift, so the settle window applies`() {
        // Neither side has acted since the last exchange: this is the continuous correction, and it
        // must still be rate-limited or the two devices would seek at each other.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
                peer = peer(songId = 200L, positionMs = 0L, seq = 5L),
                lastAppliedAt = 9_900L,
                now = 10_000L,
                localSeq = 5L,
            ),
        )
        // ...and once the window passes, it is applied.
        assertEquals(
            SyncAction.PlaySong(200L, 0L),
            TogetherSync.decide(
                local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
                peer = peer(songId = 200L, positionMs = 0L, seq = 5L),
                lastAppliedAt = 1_000L,
                now = 10_000L,
                localSeq = 5L,
            ),
        )
    }

    @Test
    fun `the next counter always exceeds both sides, without needing synced clocks`() {
        // Lamport: the new value is greater than anything either side has used, so "larger" means
        // "causally later" even though the two clocks differ by tens of seconds.
        assertEquals(1L, TogetherSync.nextSeq(0L, 0L))
        assertEquals(6L, TogetherSync.nextSeq(5L, 3L))
        assertEquals(8L, TogetherSync.nextSeq(3L, 7L))
        assertTrue(TogetherSync.nextSeq(5L, 3L) > 5L)
        assertTrue(TogetherSync.nextSeq(3L, 7L) > 7L)
    }
}
