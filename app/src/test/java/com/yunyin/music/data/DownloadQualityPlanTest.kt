// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.download.DownloadQualityPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download quality fallback order.
 *
 * The user's current tier is the preference, and a lower-tier full track is a better download than a failure; tiers
 * above the preference are not tried because that spends data the user did not ask for.
 */
class DownloadQualityPlanTest {

    @Test
    fun `exhigh falls back down through higher and standard`() {
        assertEquals(listOf("exhigh", "higher", "standard"), DownloadQualityPlan.order("exhigh"))
    }

    @Test
    fun `lossless keeps the lossless-first order`() {
        assertEquals(listOf("lossless", "exhigh", "higher", "standard"), DownloadQualityPlan.order("lossless"))
    }

    @Test
    fun `hires tries every tier in order`() {
        assertEquals(DownloadQualityPlan.TIERS, DownloadQualityPlan.order("hires"))
    }

    @Test
    fun `standard has nothing below it`() {
        assertEquals(listOf("standard"), DownloadQualityPlan.order("standard"))
    }

    @Test
    fun `an unknown preference falls back to the default`() {
        assertEquals(DownloadQualityPlan.order("bogus"), DownloadQualityPlan.order("exhigh"))
        assertTrue(DownloadQualityPlan.order("bogus").first() == "exhigh")
    }
}
