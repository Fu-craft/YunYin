// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeRulesTest {

    private fun record(
        url: String = "https://cdn/x?sig=1",
        downloadedBytes: Long = 1024,
        phase: DownloadPhase = DownloadPhase.PAUSED,
    ) = DownloadRecord(
        track = com.yunyin.music.core.Track(id = 1, name = "song", artists = listOf("artist"), albumName = "album", albumId = 2, coverUrl = null, durationMs = 10_000),
        url = url,
        downloadedBytes = downloadedBytes,
        phase = phase,
    )

    @Test
    fun `matching url resumes from the recorded byte`() {
        assertEquals(1024L, DownloadResumeRules.resumeOffset(record(), "https://cdn/x?sig=1"))
    }

    @Test
    fun `a changed url discards the partial file`() {
        assertEquals(0L, DownloadResumeRules.resumeOffset(record(), "https://cdn/x?sig=2"))
    }

    @Test
    fun `a completed record is never resumed`() {
        assertEquals(0L, DownloadResumeRules.resumeOffset(record(phase = DownloadPhase.DONE), "https://cdn/x?sig=1"))
    }

    @Test
    fun `no record starts at zero`() {
        assertEquals(0L, DownloadResumeRules.resumeOffset(null, "https://cdn/x"))
    }

    @Test
    fun `range header is only sent when resuming`() {
        assertNull(DownloadResumeRules.rangeHeader(0))
        assertEquals("bytes=1024-", DownloadResumeRules.rangeHeader(1024))
    }

    @Test
    fun `content range parsing reads start and total`() {
        assertEquals(1024L, DownloadResumeRules.contentRangeStart("bytes 1024-2047/4096"))
        assertEquals(4096L, DownloadResumeRules.contentRangeTotal("bytes 1024-2047/4096"))
        assertNull(DownloadResumeRules.contentRangeStart("garbage"))
        assertNull(DownloadResumeRules.contentRangeTotal("bytes 1-2"))
    }

    @Test
    fun `a 206 at the requested offset is a real resume`() {
        assertTrue(DownloadResumeRules.responseIsResume(206, 1024, "bytes 1024-2047/4096"))
    }

    @Test
    fun `a mismatched or zero-offset 206 is not a resume`() {
        assertFalse(DownloadResumeRules.responseIsResume(206, 1024, "bytes 0-2047/4096"))
        assertFalse(DownloadResumeRules.responseIsResume(206, 0, "bytes 0-2047/4096"))
        assertFalse(DownloadResumeRules.responseIsResume(200, 1024, null))
    }

    @Test
    fun `a 200 to a ranged request means the server ignored Range`() {
        assertTrue(DownloadResumeRules.mustRestart(200, 1024))
        assertFalse(DownloadResumeRules.mustRestart(206, 1024))
        assertFalse(DownloadResumeRules.mustRestart(200, 0))
    }

    @Test
    fun `progress is absolute and clamped`() {
        assertEquals(0.5f, DownloadResumeRules.progress(50, 100)!!, 1e-6f)
        assertEquals(1f, DownloadResumeRules.progress(150, 100)!!, 0f)
        assertEquals(0f, DownloadResumeRules.progress(0, 100)!!, 0f)
        assertNull(DownloadResumeRules.progress(50, null))
        assertNull(DownloadResumeRules.progress(50, 0))
    }
}
