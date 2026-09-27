package com.yunyin.music.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The source race.
 *
 * The bug this guards is invisible in the source: a `coroutineScope { launch { … }; launch { … };
 * receive() }` looks like it returns on the first result, but `coroutineScope` waits for its children,
 * so it actually waits for **both** producers. The lyric load raced a fast NetEase fetch against slow
 * AMLL mirrors, so the slow one decided the latency — and when it exceeded the caller's budget the
 * load returned "no lyrics" for a song whose lyrics had already arrived.
 */
class LyricSourceRaceTest {

    @Test
    fun `returns the fast result without waiting for the slow one`() = runBlocking {
        val finished = withTimeoutOrNull(2_000) {
            raceFirstOf(
                first = { delay(10); "fast" },
                second = { delay(30_000); "slow" },
            )
        }
        // If the race waited for the slow producer this would be null (timed out) or "slow".
        assertEquals("fast", finished)
    }

    @Test
    fun `a null from the fast side does not cancel the search`() = runBlocking {
        // One source answering "I have nothing" must not end the race — the other may still have it.
        val finished = raceFirstOf(
            first = { delay(10); null },
            second = { delay(60); "slow" },
        )
        assertEquals("slow", finished)
    }

    @Test
    fun `both sides empty completes rather than hanging`() = runBlocking {
        // Without closing the channel, a pair of legitimate "no lyrics" answers would suspend
        // forever on a receive that can never be satisfied — a permanent loading spinner.
        val finished = withTimeoutOrNull(2_000) {
            raceFirstOf(first = { delay(10); null }, second = { delay(20); null })
        }
        assertNull(finished)
        // Reaching here at all means it completed; distinguish from a timeout by re-running fast.
        val quick = raceFirstOf(first = { null }, second = { null })
        assertNull(quick)
    }

    @Test
    fun `the slower producer is abandoned, not awaited`() = runBlocking {
        // Measures elapsed time directly: the fast side wins at ~10ms, so the call must not take
        // anything like the slow side's 5s.
        val started = System.nanoTime()
        val result = raceFirstOf(
            first = { delay(5); "winner" },
            second = { delay(5_000); "loser" },
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("winner", result)
        org.junit.Assert.assertTrue(
            "the race took ${elapsedMs}ms, so it waited for the slow producer",
            elapsedMs < 1_000,
        )
    }

    @Test
    fun `a failing producer does not sink a successful one`() = runBlocking {
        // One source being unreachable is the normal case for the AMLL mirrors; the other source's
        // answer must still come through rather than the whole race failing.
        val result = raceFirstOf(
            first = { delay(5); throw java.io.IOException("mirror down") },
            second = { delay(20); "survivor" },
        )
        assertEquals("survivor", result)
    }
}
