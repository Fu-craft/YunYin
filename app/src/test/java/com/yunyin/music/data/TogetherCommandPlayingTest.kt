package com.yunyin.music.data

import com.yunyin.music.data.together.commandPlaying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a room command's play state is read.
 *
 * The reference implementation's rule, adopted verbatim: a *start* command means playing, and so does an
 * explicit `PLAY` status; only `PAUSE` means paused. The two halves are OR'ed.
 *
 * Why that matters, in both directions, because this function has now been wrong both ways:
 *
 *  - trusting `playStatus` **over** the type left a follower paused on a track the sender was playing, so
 *    the room could never start ("还是无法一起播放") — the `GOTO` carrying the track change read as paused;
 *  - the OR is what the reference does, and it is the safer failure: a room that keeps playing beats a room
 *    that never starts.
 */
class TogetherCommandPlayingTest {

    @Test
    fun `a start command means playing, whatever the status says`() {
        // This is the case that broke the room: the track-change command is a start, and it must be read as
        // playing even if the echoed status lags a beat behind.
        assertTrue(commandPlaying(type = "GOTO", playStatus = "PAUSE"))
        assertTrue(commandPlaying(type = "NEXT", playStatus = ""))
        assertTrue(commandPlaying(type = "PREV", playStatus = ""))
        assertTrue(commandPlaying(type = "PLAY", playStatus = ""))
    }

    @Test
    fun `an explicit play status means playing even without a start command`() {
        assertTrue(commandPlaying(type = "PROGRESS", playStatus = "PLAY"))
        assertTrue(commandPlaying(type = "PROGRESS", playStatus = "playing"))
    }

    @Test
    fun `only a pause means paused`() {
        assertFalse(commandPlaying(type = "PAUSE", playStatus = "PAUSE"))
        assertFalse(commandPlaying(type = "PROGRESS", playStatus = "PAUSE"))
        assertFalse(commandPlaying(type = "PROGRESS", playStatus = "paused"))
        assertFalse(commandPlaying(type = "", playStatus = ""))
    }
}
