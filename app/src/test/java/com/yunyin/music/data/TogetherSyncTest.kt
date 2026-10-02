package com.yunyin.music.data

import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The listen-together sync rule.
 *
 * The cases here are the ways two devices correcting each other go wrong — the failure this feature is most
 * likely to ship with, because it looks like it works when you test it alone:
 *
 *  - **the first song syncs and nothing after it** (the reported bug): a stale ordering rule made every later
 *    peer command compare as "older" and be ignored;
 *  - the ping-pong: A seeks, B seeks back, forever (guarded by the settle window);
 *  - correcting a sub-second skew, which is inaudible as desync but very audible as a stutter;
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
            TogetherSync.decide(local, null, peerIsNew = true, lastAppliedAt = 0L, now = 10_000L),
        )
    }

    @Test
    fun `a peer who has not started a song is not followed`() {
        // A member who joined the room but has no track yet: following it would stop the music for
        // everyone, which is the opposite of what they asked for.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(local, peer(songId = 0L, positionMs = 0L), true, 0L, now = 10_000L),
        )
    }

    @Test
    fun `a different song is adopted with the peer's position`() {
        val action = TogetherSync.decide(
            local,
            peer(songId = 200L, positionMs = 30_000L),
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.PlaySong(200L, 30_000L), action)
    }

    @Test
    fun `a second, later command is followed too`() {
        // THE reported bug. With the old counter comparison, adopting the peer's first command advanced our
        // value past theirs, so every *later* command compared as older and was ignored: "the first song is
        // the same, but when the host switches, the member does not follow."
        //
        // With identity-based ordering there is no such state: a command with a different song is simply a
        // new thing the peer did. Modelled here as the state after having adopted the first command.
        val afterFirst = LocalPlayback(songId = 200L, positionMs = 0L, playing = true)

        val second = TogetherSync.decide(
            local = afterFirst,
            peer = peer(songId = 300L, positionMs = 0L, seq = 2L),
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 20_000L,
        )
        assertEquals(SyncAction.PlaySong(300L, 0L), second)
    }

    @Test
    fun `a peer command is followed even right after we applied one`() {
        // The settle window must not suppress an explicit action: the peer pressed next and expects the room
        // to move now, whatever we were doing a moment ago.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 200L, positionMs = 0L, playing = true),
            peer = peer(songId = 300L, positionMs = 5_000L, seq = 3L),
            peerIsNew = true,
            lastAppliedAt = 9_900L,
            now = 10_000L,
        )
        assertEquals(SyncAction.PlaySong(300L, 5_000L), action)
    }

    @Test
    fun `a repeated command is left alone inside the settle window`() {
        // Same command as last time, and we just applied something: this is drift, and chasing it inside the
        // window is what makes the two devices seek at each other.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
            peer = peer(songId = 300L, positionMs = 30_000L, seq = 3L),
            peerIsNew = false,
            lastAppliedAt = 9_000L,
            now = 10_000L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a repeated command is never applied, however out of step the position is`() {
        // The rule that stops the two devices fighting: only a *new* command moves the player. With a
        // periodic drift branch here, "they paused and I seek back to playing" and "I play and they pull me
        // back to paused" chase each other forever — every correction reads as a difference worth
        // correcting. The reference has no such branch, and this pins its absence.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                local = LocalPlayback(songId = 300L, positionMs = 30_000L, playing = true),
                peer = peer(songId = 300L, positionMs = 34_000L, seq = 3L),
                peerIsNew = false,
                lastAppliedAt = 1_000L,
                now = 10_000L,
            ),
        )
        // Even the play state: a repeated command must not flip us.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                local = LocalPlayback(songId = 300L, positionMs = 30_000L, playing = true),
                peer = peer(songId = 300L, positionMs = 30_000L, playing = false, seq = 3L),
                peerIsNew = false,
                lastAppliedAt = 1_000L,
                now = 10_000L,
            ),
        )
    }

    @Test
    fun `a drift past the tolerance is corrected by seeking`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 34_000L),
            peerIsNew = true,
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
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.None, action)
    }

    @Test
    fun `a drift exactly at the tolerance is not corrected`() {
        // Boundary: the test is `> tolerance`, so exactly at it stays put. Pinned because an off-by-one here
        // turns every steady state into a seek loop.
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 32_000L),
            peerIsNew = true,
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
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Seek(200L, 30_000L), action)
    }

    @Test
    fun `same song and position but the peer paused pauses us`() {
        val action = TogetherSync.decide(
            LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer(songId = 200L, positionMs = 30_000L, playing = false),
            peerIsNew = true,
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
            peerIsNew = true,
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
                peerIsNew = true,
                lastAppliedAt = 0L,
                now = 10_000L,
            ),
        )
    }

    @Test
    fun `a stale report of a different song never yanks our track in drift mode`() {
        // Not a new command, yet a different song: the only reading is a stale echo of a track we have
        // already left. Drift correction must not pull us back to it.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                local = LocalPlayback(songId = 300L, positionMs = 0L, playing = true),
                peer = peer(songId = 200L, positionMs = 90_000L, seq = 3L),
                peerIsNew = false,
                lastAppliedAt = 1_000L,
                now = 10_000L,
            ),
        )
    }

    // ---------------------------------------------------------------- command kinds
    //
    // The official protocol says *what the peer did* (`GOTO`/`NEXT`/`PREV`/`PROGRESS`/`PLAY`/`PAUSE`), and
    // the rule acts on that rather than inferring it. Inferring made the two devices invert each other:
    // a pause arrived as a position mismatch, was read as "seek", and a seek never stops the music — so the
    // receiver kept playing ("我暂停他那边还在播") and the mirror case left it paused ("我播放他那边暂停").

    private fun typed(
        type: String,
        songId: Long = 200L,
        positionMs: Long = 30_000L,
        playing: Boolean = true,
    ) = TogetherPeerState(
        uid = "peer",
        name = "听众",
        songId = songId,
        positionMs = positionMs,
        playing = playing,
        updatedAt = 0L,
        seq = 1L,
        commandType = type,
    )

    @Test
    fun `a PAUSE pauses even when the positions are far apart`() {
        // The inversion's exact shape: positions differ by 10s, which the legacy rule would answer with a
        // seek. The command says PAUSE, so it must be a pause regardless of the gap.
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 200L, positionMs = 0L, playing = true),
            peer = typed("PAUSE", positionMs = 10_000L, playing = false),
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Pause, action)
    }

    @Test
    fun `a PLAY plays even when the positions are far apart`() {
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 200L, positionMs = 0L, playing = false),
            peer = typed("PLAY", positionMs = 10_000L, playing = true),
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Play, action)
    }

    @Test
    fun `a PROGRESS seeks and leaves the play state alone`() {
        val action = TogetherSync.decide(
            local = LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
            peer = typed("PROGRESS", positionMs = 45_000L, playing = true),
            peerIsNew = true,
            lastAppliedAt = 0L,
            now = 10_000L,
        )
        assertEquals(SyncAction.Seek(200L, 45_000L), action)
    }

    @Test
    fun `a GOTO to another track loads it, and to the same track just starts it`() {
        // Different track: load and start it.
        assertEquals(
            SyncAction.PlaySong(300L, 0L),
            TogetherSync.decide(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                typed("GOTO", songId = 300L, positionMs = 0L),
                peerIsNew = true, lastAppliedAt = 0L, now = 10_000L,
            ),
        )
        // Same track, but we are paused: the peer restarted it, so start — do not reload.
        assertEquals(
            SyncAction.Play,
            TogetherSync.decide(
                LocalPlayback(songId = 200L, positionMs = 0L, playing = false),
                typed("GOTO", songId = 200L, positionMs = 0L),
                peerIsNew = true, lastAppliedAt = 0L, now = 10_000L,
            ),
        )
    }

    @Test
    fun `NEXT and PREV behave as a track change`() {
        // The member side of "他那边无法进行上一首下一首的操作": a next/previous must load the target track,
        // not be treated as a position nudge.
        listOf("NEXT", "PREV").forEach { type ->
            assertEquals(
                type,
                SyncAction.PlaySong(300L, 0L),
                TogetherSync.decide(
                    LocalPlayback(songId = 200L, positionMs = 30_000L, playing = true),
                    typed(type, songId = 300L, positionMs = 0L),
                    peerIsNew = true, lastAppliedAt = 0L, now = 10_000L,
                ),
            )
        }
    }

    @Test
    fun `a GOTO carries the sender's play state, so a paused switch lands paused`() {
        // A peer that switched tracks while paused must not start playing on the receiver: that is how a
        // paused sender ends up with a playing receiver. (The read path reports "playing" for a start
        // command, so this is the belt to that braces — the rule honours whatever state it is handed.)
        assertEquals(
            SyncAction.PlaySong(300L, 0L, playing = false),
            TogetherSync.decide(
                LocalPlayback(songId = 200L, positionMs = 30_000L, playing = false),
                typed("GOTO", songId = 300L, positionMs = 0L, playing = false),
                peerIsNew = true, lastAppliedAt = 0L, now = 10_000L,
            ),
        )
    }

    @Test
    fun `a repeated typed command is still never applied`() {
        // The anti-ping-pong rule holds for typed commands too: an old PAUSE must not keep re-pausing us.
        assertEquals(
            SyncAction.None,
            TogetherSync.decide(
                LocalPlayback(songId = 200L, positionMs = 0L, playing = false),
                typed("PAUSE", positionMs = 10_000L, playing = false),
                peerIsNew = false, lastAppliedAt = 1_000L, now = 10_000L,
            ),
        )
    }

    @Test
    fun `the identity distinguishes a PLAY from a PROGRESS on the same track`() {
        // Both can carry the same song and position, but only one starts the music — so they are different
        // commands and must not collapse into one identity.
        assertNotEquals(
            TogetherSync.peerIdentity(typed("PLAY")),
            TogetherSync.peerIdentity(typed("PROGRESS")),
        )
    }

    // ---------------------------------------------------------------- command identity

    private val base = peer(songId = 200L, positionMs = 30_000L, playing = true)

    @Test
    fun `the identity ignores position, so listening is not an action`() {
        // Position advances on its own; if it were part of the identity, every poll would look like a new
        // command and the drift guard would never apply.
        assertEquals(
            TogetherSync.peerIdentity(base),
            TogetherSync.peerIdentity(base.copy(positionMs = 45_000L)),
        )
    }

    @Test
    fun `the identity changes when the peer does something`() {
        assertNotEquals(TogetherSync.peerIdentity(base), TogetherSync.peerIdentity(base.copy(songId = 300L)))
        assertNotEquals(TogetherSync.peerIdentity(base), TogetherSync.peerIdentity(base.copy(playing = false)))
        assertNotEquals(TogetherSync.peerIdentity(base), TogetherSync.peerIdentity(base.copy(uid = "other")))
    }
}
