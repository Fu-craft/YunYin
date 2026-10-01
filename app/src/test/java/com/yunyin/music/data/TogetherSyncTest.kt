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
    fun `nothing is applied while a correction is still settling`() {
        // The ping-pong guard: right after we applied the peer's state, a fake drift must be ignored
        // rather than chased. Without this, two devices correct each other forever.
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 999L, positionMs = 0L),
            lastAppliedAt = 9_000L,
            now = 10_000L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a correction is applied again once the settle window has passed`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 999L, positionMs = 0L),
            lastAppliedAt = 1_000L,
            now = 10_000L,
            settleMs = 6_000L,
        )
        assertEquals(SyncAction.PlaySong(999L, 0L), action)
    }

    @Test
    fun `tie-break prefers the peer only when its sequence is newer`() {
        assertTrue(TogetherSync.peerHasPriority(peer(songId = 1L, positionMs = 0L, seq = 5L), 4L))
        assertFalse(TogetherSync.peerHasPriority(peer(songId = 1L, positionMs = 0L, seq = 4L), 4L))
        assertFalse(TogetherSync.peerHasPriority(peer(songId = 1L, positionMs = 0L, seq = 3L), 4L))
    }
}
