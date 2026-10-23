package com.yunyin.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSchedulerRulesTest {

    private fun decide(
        existing: DownloadPhase? = null,
        active: Int = 0,
        wifiOnly: Boolean = false,
        onWifi: Boolean = true,
        availableBytes: Long = 1024L * 1024L * 1024L,
        minimumFreeBytes: Long = DownloadSchedulerRules.MIN_FREE_BYTES,
    ) = DownloadSchedulerRules.decide(existing, active, wifiOnly, onWifi, availableBytes, minimumFreeBytes)

    @Test
    fun `starts below the concurrency cap`() {
        assertEquals(DownloadSchedulerRules.Decision.START, decide(active = 1))
    }

    @Test
    fun `queues at the concurrency cap`() {
        assertEquals(DownloadSchedulerRules.Decision.QUEUE, decide(active = DownloadSchedulerRules.MAX_CONCURRENT))
    }

    @Test
    fun `an active track is not enqueued twice`() {
        assertEquals(DownloadSchedulerRules.Decision.ALREADY_ACTIVE, decide(existing = DownloadPhase.DOWNLOADING))
        assertEquals(DownloadSchedulerRules.Decision.ALREADY_ACTIVE, decide(existing = DownloadPhase.WAITING))
        assertEquals(DownloadSchedulerRules.Decision.ALREADY_ACTIVE, decide(existing = DownloadPhase.RESOLVING))
    }

    @Test
    fun `a completed track is not downloaded again`() {
        assertEquals(DownloadSchedulerRules.Decision.ALREADY_DONE, decide(existing = DownloadPhase.DONE))
    }

    @Test
    fun `wifi-only blocks a mobile connection`() {
        assertEquals(DownloadSchedulerRules.Decision.WIFI_BLOCKED, decide(wifiOnly = true, onWifi = false))
        assertEquals(DownloadSchedulerRules.Decision.START, decide(wifiOnly = true, onWifi = true))
        assertEquals(DownloadSchedulerRules.Decision.START, decide(wifiOnly = false, onWifi = false))
    }

    @Test
    fun `too little free space blocks the download`() {
        assertEquals(
            DownloadSchedulerRules.Decision.NO_SPACE,
            decide(availableBytes = DownloadSchedulerRules.MIN_FREE_BYTES - 1),
        )
        assertEquals(DownloadSchedulerRules.Decision.START, decide(availableBytes = DownloadSchedulerRules.MIN_FREE_BYTES))
    }

    @Test
    fun `batch skips downloaded active and paused and keeps failed`() {
        val phases = mapOf(
            1L to DownloadPhase.DONE,
            2L to DownloadPhase.DOWNLOADING,
            3L to DownloadPhase.PAUSED,
            4L to DownloadPhase.FAILED,
            5L to DownloadPhase.IDLE,
        )
        assertEquals(listOf(4L, 5L), DownloadSchedulerRules.batchCandidates(listOf(1, 2, 3, 4, 5), phases))
    }

    @Test
    fun `batch de-duplicates ids and preserves order`() {
        assertEquals(
            listOf(9L, 7L),
            DownloadSchedulerRules.batchCandidates(listOf(9, 7, 9, 7), emptyMap()),
        )
    }

    @Test
    fun `retry is allowed only for missing failed or paused`() {
        assertTrue(DownloadSchedulerRules.canRetry(null))
        assertTrue(DownloadSchedulerRules.canRetry(DownloadPhase.FAILED))
        assertTrue(DownloadSchedulerRules.canRetry(DownloadPhase.PAUSED))
        assertFalse(DownloadSchedulerRules.canRetry(DownloadPhase.DONE))
        assertFalse(DownloadSchedulerRules.canRetry(DownloadPhase.DOWNLOADING))
    }
}
