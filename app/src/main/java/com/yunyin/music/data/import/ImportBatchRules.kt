package com.yunyin.music.data.import

/**
 * Pure scheduling/feedback rules for the import worker pool.
 *
 * The import must always terminate and always show movement: a bounded number of workers (4) drain a shared
 * cursor, every request has an 8 s timeout, and progress is a simple resolved/total fraction. Keeping these
 * decisions here makes the concurrency cap, timeout classification, progress math, enablement rule and direct-id
 * path testable without Android or coroutines.
 */
object ImportBatchRules {

    /** At most this many search requests are in flight; sequential search made a 30-line paste look frozen. */
    const val MAX_CONCURRENCY = 4

    /** Per-request budget. A slow search fails as an error row instead of stalling the whole batch. */
    const val REQUEST_TIMEOUT_MS = 8_000L

    /** Overall budget for fetching a NetEase playlist (paged reads can take longer than one search). */
    const val PLAYLIST_TIMEOUT_MS = 20_000L

    /** The primary action is enabled by input presence alone; parsing/validation happens after the tap. */
    fun canStart(input: String): Boolean = input.isNotBlank()

    /** Number of workers actually used: never more than the cap, never more than the work. */
    fun workers(total: Int): Int = minOf(MAX_CONCURRENCY, total.coerceAtLeast(0))

    /** 0 until the last row resolves; exactly 1 when every row has been classified. */
    fun progress(resolved: Int, total: Int): Float {
        if (total <= 0) return 0f
        return (resolved.toFloat() / total).coerceIn(0f, 1f)
    }

    enum class FailureKind { TIMEOUT, ERROR, CANCELLED }

    fun classifyFailure(isTimeout: Boolean, isCancellation: Boolean = false): FailureKind = when {
        isCancellation -> FailureKind.CANCELLED
        isTimeout -> FailureKind.TIMEOUT
        else -> FailureKind.ERROR
    }

    /**
     * Direct-id import: a NetEase playlist's own tracks already carry canonical NetEase ids, so the link path
     * skips search/matching entirely. This is both faster and more accurate than re-searching known tracks.
     */
    fun directIds(tracks: List<Long>): List<Long> = tracks.filter { it > 0L }.distinct()
}
