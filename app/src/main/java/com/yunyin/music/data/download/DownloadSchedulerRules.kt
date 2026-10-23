package com.yunyin.music.data.download

/**
 * Pure scheduling decisions for the download queue.
 *
 * The manager already runs at most [MAX_CONCURRENT] downloads; these rules make the rest of the policy testable
 * without Android: duplicate suppression, the Wi-Fi-only gate, and the free-space check. All values are explicit so
 * the caller supplies the live settings/network/storage readings rather than hiding them here.
 */
object DownloadSchedulerRules {

    /** Two concurrent downloads at most: enough to use the link, few enough not to trigger CDN rate limits. */
    const val MAX_CONCURRENT = 2

    /** Reserve below which a new download is refused. The real total is only known after the probe. */
    const val MIN_FREE_BYTES = 50L * 1024L * 1024L

    enum class Decision { START, QUEUE, ALREADY_ACTIVE, ALREADY_DONE, WIFI_BLOCKED, NO_SPACE }

    private val ACTIVE_PHASES = setOf(
        DownloadPhase.WAITING, DownloadPhase.RESOLVING, DownloadPhase.DOWNLOADING,
    )

    /** Phase is a running/queued download (waiting counts so a queue tap does not enqueue twice). */
    fun isActive(phase: DownloadPhase?): Boolean = phase != null && phase in ACTIVE_PHASES

    /**
     * @param existing phase already known for the track, null when never seen
     * @param active currently running downloads
     * @param onWifi true when the active network is Wi-Fi (or Ethernet)
     */
    fun decide(
        existing: DownloadPhase?,
        active: Int,
        wifiOnly: Boolean,
        onWifi: Boolean,
        availableBytes: Long,
        minimumFreeBytes: Long = MIN_FREE_BYTES,
    ): Decision = when {
        existing == DownloadPhase.DONE -> Decision.ALREADY_DONE
        isActive(existing) -> Decision.ALREADY_ACTIVE
        wifiOnly && !onWifi -> Decision.WIFI_BLOCKED
        availableBytes < minimumFreeBytes -> Decision.NO_SPACE
        active < MAX_CONCURRENT -> Decision.START
        else -> Decision.QUEUE
    }

    /**
     * Tracks from a batch that should be enqueued: downloaded, active and paused items are skipped so "下载全部"
     * never downloads the same song twice. Preserves input order and de-duplicates duplicate ids inside the batch.
     */
    fun batchCandidates(
        trackIds: List<Long>,
        knownPhases: Map<Long, DownloadPhase>,
    ): List<Long> {
        val seen = HashSet<Long>()
        return trackIds.filter { id ->
            if (!seen.add(id)) return@filter false
            when (knownPhases[id]) {
                null, DownloadPhase.IDLE, DownloadPhase.FAILED -> true
                DownloadPhase.DONE, DownloadPhase.WAITING, DownloadPhase.RESOLVING,
                DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED -> false
            }
        }
    }

    /** A failed download may be retried; a completed one may not be queued again. */
    fun canRetry(existing: DownloadPhase?): Boolean = existing == null || existing == DownloadPhase.FAILED || existing == DownloadPhase.PAUSED
}
