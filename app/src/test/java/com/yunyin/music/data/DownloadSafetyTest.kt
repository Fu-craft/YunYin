// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.download.AudioResponseCheck
import com.yunyin.music.data.download.DownloadProbeVerdict
import com.yunyin.music.data.download.DownloadPreviewGate
import com.yunyin.music.data.download.DownloadQualityPlan
import com.yunyin.music.data.download.DownloadStoragePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download safety rules: what is refused, what is written where, and what a broken response body looks like.
 *
 * The preview rule was corrected after "nothing downloaded": refusing on an `UNKNOWN` probe vetoed downloads for a
 * probe that simply could not determine the size, which is common on CDNs. Only a **classified** preview (or the
 * resolver's own `isTrial`) is positive evidence.
 */
class DownloadSafetyTest {

    private val fourMinutes = 240_000L

    @Test
    fun `a classified preview clip is refused`() {
        assertTrue(DownloadPreviewGate.refuse(DownloadProbeVerdict.PREVIEW_CLIP, 400_000L, fourMinutes))
        assertTrue(DownloadPreviewGate.refuse(DownloadProbeVerdict.PREVIEW_CLIP, null, fourMinutes))
    }

    @Test
    fun `an unknown probe never vetoes a download`() {
        // A HEAD that is blocked, has no Content-Length, or a refused range must not categorically refuse the file.
        assertFalse(DownloadPreviewGate.refuse(DownloadProbeVerdict.UNKNOWN, null, fourMinutes))
        assertFalse(DownloadPreviewGate.refuse(DownloadProbeVerdict.UNKNOWN, 0L, fourMinutes))
        // Even a small-looking length under UNKNOWN is not positive proof (the classifier would have said PREVIEW).
        assertFalse(DownloadPreviewGate.refuse(DownloadProbeVerdict.UNKNOWN, 400_000L, fourMinutes))
    }

    @Test
    fun `a full probe is allowed`() {
        assertFalse(DownloadPreviewGate.refuse(DownloadProbeVerdict.FULL, 9_000_000L, fourMinutes))
    }

    @Test
    fun `storage folders fall back from the Chinese collection to ASCII`() {
        assertEquals(listOf("Music/云音", "Music", "Music/YunYin"), DownloadStoragePlan.folders)
        assertEquals(listOf("Music", "Music/YunYin"), DownloadStoragePlan.after("Music/云音"))
        assertEquals(listOf("Music/YunYin"), DownloadStoragePlan.after("Music"))
        assertTrue(DownloadStoragePlan.after("Music/YunYin").isEmpty())
    }

    @Test
    fun `an HTML body served with 200 is not audio`() {
        assertEquals(
            "返回的不是音频（像是网页）",
            AudioResponseCheck.failure("<!DOCTYPE html><html>login</html>".toByteArray(), "text/html"),
        )
        assertEquals(
            "返回的不是音频（像是网页）",
            AudioResponseCheck.failure("<html><body>error".toByteArray(), null),
        )
    }

    @Test
    fun `a JSON error body is not audio`() {
        assertEquals(
            "返回的不是音频（像是 JSON 错误）",
            AudioResponseCheck.failure("""{"code":403,"msg":"login required"}""".toByteArray(), "application/json"),
        )
        assertEquals(
            "返回的不是音频（像是 JSON 错误）",
            AudioResponseCheck.failure("""[{"error":1}]""".toByteArray(), null),
        )
    }

    @Test
    fun `a text content type is not audio`() {
        assertTrue(
            AudioResponseCheck.failure("some text".toByteArray(), "text/plain")!!.startsWith("返回的不是音频"),
        )
    }

    @Test
    fun `binary audio prefixes pass the sniff`() {
        // "ftyp" MP4 box, no text marker.
        assertNull(AudioResponseCheck.failure(byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()), "audio/mp4"))
    }

    @Test
    fun `quality labels are user-facing Chinese`() {
        assertEquals("极高", DownloadQualityPlan.qualityLabel("exhigh"))
        assertEquals("无损", DownloadQualityPlan.qualityLabel("lossless"))
        assertEquals("Hi-Res", DownloadQualityPlan.qualityLabel("hires"))
        assertEquals("标准", DownloadQualityPlan.qualityLabel("standard"))
    }
}
