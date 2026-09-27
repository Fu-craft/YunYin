package com.yunyin.music.data

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Returns the first non-null result of two producers, without waiting for the other.
 *
 * Written because the obvious version is wrong in a way that is invisible in the source:
 *
 * ```kotlin
 * coroutineScope {
 *     launch { a.await()?.let { channel.send(it) } }
 *     launch { b.await()?.let { channel.send(it) } }
 *     channel.receive()
 * }
 * ```
 *
 * `coroutineScope` returns only once the block **and all its children** have completed, so that form
 * waits for *both* producers even though the answer arrived from the first — the slower source
 * decides the latency. The lyric load raced a fast NetEase fetch against five AMLL mirrors; NetEase
 * answers in ~0.4s, but the wait was up to the mirror timeout, and when that exceeded the caller's
 * budget the load returned "no lyrics" for a song whose lyrics had already been fetched. The whole
 * point of racing was to be bounded by the fastest healthy source.
 *
 * Cancelling the losers — and treating "both finished with nothing" as a result rather than a
 * forever-pending receive — is what makes that true.
 *
 * The producers are suspended as children of an inner scope, so cancelling one only stops *waiting*;
 * whatever [Deferred] it was awaiting keeps running wherever it was started.
 */
internal suspend fun <T : Any> raceFirstOf(
    first: suspend () -> T?,
    second: suspend () -> T?,
): T? = coroutineScope {
    val arriving = Channel<T>(Channel.CONFLATED)
    val producers = listOf(first, second).map { produce ->
        launch {
            // A producer that throws must not sink the race.
            //
            // Without this the exception propagates out of the child, cancelling the whole scope —
            // so one unreachable source would take the other, working source down with it, and the
            // song would show "no lyrics" despite having been fetched successfully. A failing source
            // is also the *normal* state of the AMLL mirrors, so this is a routine path, not an edge
            // case.
            val value = runCatching { produce() }.getOrNull()
            value?.let { arriving.send(it) }
        }
    }
    // Closes the channel once every producer has finished, so a pair that both yield nothing
    // completes immediately instead of suspending on a receive that can never be satisfied.
    val closer = launch {
        producers.forEach { it.join() }
        arriving.close()
    }

    val result = arriving.receiveCatching().getOrNull()
    producers.forEach { it.cancel() }
    closer.cancel()
    result
}
