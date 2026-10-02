package com.yunyin.music.data

import com.yunyin.music.data.together.commandPlaying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a room command's play state is read.
 *
 * This is the one place the reported inversion can be introduced on the *reading* side, so it is pinned
 * directly. The sender writes `playStatus` from its own player; the command *kind* is a separate field that
 * can disagree with it, and picking the wrong one of the two flips the room.
 *
 * The specific trap: a `GOTO` is a start command, so it is tempting to read every `GOTO` as "playing". But a
 * `GOTO` is also sent when the sender switches tracks *while paused*, and forcing it to play then starts
 * music on a device whose partner is paused — the reported "我暂停他那边就播放", arriving through a track
 * change instead of a button.
 */
class TogetherCommandPlayingTest {

    @Test
    fun `an explicit status wins over the command kind`() {
        // A paused sender that changed track: the kind says "start", the status says "paused", and the
        // status is what the sender's own player actually reported.
        assertFalse(commandPlaying(type = "GOTO", playStatus = "PAUSE"))
        assertFalse(commandPlaying(type = "NEXT", playStatus = "PAUSE"))
        assertFalse(commandPlaying(type = "PREV", playStatus = "PAUSED"))
        assertTrue(commandPlaying(type = "PAUSE", playStatus = "PLAY"))
    }

    @Test
    fun `the kind is the fallback when no status is given`() {
        assertTrue(commandPlaying(type = "PLAY", playStatus = ""))
        assertTrue(commandPlaying(type = "GOTO", playStatus = ""))
        assertTrue(commandPlaying(type = "NEXT", playStatus = ""))
        assertTrue(commandPlaying(type = "PREV", playStatus = ""))
        assertFalse(commandPlaying(type = "PROGRESS", playStatus = ""))
        assertFalse(commandPlaying(type = "PAUSE", playStatus = ""))
    }

    @Test
    fun `status matching is case-insensitive`() {
        assertTrue(commandPlaying(type = "PROGRESS", playStatus = "play"))
        assertTrue(commandPlaying(type = "PROGRESS", playStatus = "Playing"))
        assertFalse(commandPlaying(type = "PLAY", playStatus = "paused"))
    }
}
