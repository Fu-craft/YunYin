// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.lxsource.ResolutionConsultPolicy
import com.yunyin.music.data.net.NeteaseClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Skip the custom source" rule.
 *
 * The source is only worth a JS-engine call and a network hop when it is enabled **and** NetEase's own answer is not
 * usable. A full NetEase URL means the source is skipped entirely (the old code always paid the source first).
 */
class ResolutionConsultPolicyTest {

    private fun url(value: String = "https://cdn/x.mp3") = NeteaseClient.SongUrl(url = value)

    @Test
    fun `no enabled source means it is never consulted`() {
        assertFalse(ResolutionConsultPolicy.needsSource(false, url()))
        assertFalse(ResolutionConsultPolicy.needsSource(false, null))
        assertFalse(ResolutionConsultPolicy.needsSource(false, NeteaseClient.SongUrl(null, trialEndMs = 45_000)))
    }

    @Test
    fun `a full NetEase URL skips the source`() {
        assertFalse(ResolutionConsultPolicy.needsSource(true, url()))
    }

    @Test
    fun `a trial, unavailable or missing NetEase answer needs the source`() {
        val trial = NeteaseClient.SongUrl(url = "https://cdn/preview.mp3", trialEndMs = 45_000)
        assertTrue(ResolutionConsultPolicy.needsSource(true, trial))
        assertTrue(ResolutionConsultPolicy.needsSource(true, NeteaseClient.SongUrl(url = null, code = 404)))
        assertTrue(ResolutionConsultPolicy.needsSource(true, NeteaseClient.SongUrl(url = null)))
        assertTrue(ResolutionConsultPolicy.needsSource(true, null))
    }
}
