package com.yunyin.music.data

import com.yunyin.music.data.together.TogetherMember
import com.yunyin.music.data.together.TogetherPeerState
import com.yunyin.music.data.together.TogetherResult
import com.yunyin.music.data.together.TogetherSession
import com.yunyin.music.data.together.TogetherTransport
import com.yunyin.music.data.together.TogetherUiState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failing transport must produce a **message**, never an exception that escapes.
 *
 * This is the crash users hit: tapping join a few times killed the app. The session's scope is
 * `Dispatchers.Main.immediate` on its own SupervisorJob, so an exception thrown out of one of its
 * coroutines is swallowed nowhere — it arrives as an uncaught exception and takes the process down.
 * Network code must not have that power.
 *
 * The second symptom is also encoded here: repeated taps used to start independent jobs, so a second
 * join raced the first and (in the transport) leaked a connection each time.
 */
class TogetherSessionFailureTest {

    /** A transport whose create/join always throws, standing in for a broken network stack. */
    private class ThrowingTransport : TogetherTransport {
        override val configured = true

        override suspend fun createRoom(uid: String, name: String): TogetherResult<String> =
            throw IllegalStateException("boom")

        override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
            TogetherResult.Ok(emptyList())

        override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> =
            throw IllegalStateException("boom")

        override suspend fun postState(
            code: String,
            uid: String,
            name: String,
            songId: Long,
            positionMs: Long,
            playing: Boolean,
            seq: Long,
        ): TogetherResult<List<TogetherPeerState>> = TogetherResult.Ok(emptyList())

        override suspend fun pollPeers(
            code: String,
            excludeUid: String,
        ): TogetherResult<List<TogetherPeerState>> = TogetherResult.Ok(emptyList())

        override suspend fun leave(code: String, uid: String): TogetherResult<Unit> =
            TogetherResult.Ok(Unit)

        override suspend fun closeRoom(code: String): TogetherResult<Unit> =
            TogetherResult.Ok(Unit)
    }

    /**
     * A scope that records anything escaping it.
     *
     * If the session let the exception out, the handler fires and [escaped] is set — which is exactly
     * the difference between "shows an error" and "the app dies".
     */
    private fun recordingScope(sink: (Throwable) -> Unit): CoroutineScope =
        CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, e -> sink(e) })

    @Test
    fun `a throwing transport becomes an error message, not a crash`() = runBlocking {
        var escaped: Throwable? = null
        val session = TogetherSession(
            transport = ThrowingTransport(),
            scope = recordingScope { escaped = it },
        )

        session.createRoom(uid = "me", name = "我")

        // Wait for the attempt to settle: the session clears `connecting` in its finally block.
        var waited = 0
        while (session.state.value.connecting && waited < 200) {
            delay(10)
            waited++
        }

        assertNull("the exception must not escape the session", escaped)
        assertEquals("boom", session.state.value.error)
        assertTrue("connecting must be cleared afterwards", !session.state.value.connecting)
    }

    @Test
    fun `a throwing join is reported the same way`() = runBlocking {
        var escaped: Throwable? = null
        val session = TogetherSession(
            transport = ThrowingTransport(),
            scope = recordingScope { escaped = it },
        )

        session.joinRoom(code = "ABCD2345WXYZ", uid = "me", name = "我")
        var waited = 0
        while (session.state.value.connecting && waited < 200) {
            delay(10)
            waited++
        }

        assertNull(escaped)
        assertEquals("boom", session.state.value.error)
        // Not in a room: a failed join must not leave a code behind.
        assertTrue(!session.state.value.inRoom)
    }

    @Test
    fun `the initial state is idle and takes its code length from the transport`() {
        val session = TogetherSession(
            transport = ThrowingTransport(),
            scope = recordingScope { },
        )
        val state: TogetherUiState = session.state.value
        assertNull(state.error)
        assertTrue(!state.connecting)
        assertEquals(12, state.codeLength)
    }
}
