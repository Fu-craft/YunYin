// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.download

/**
 * Quality fallback order for a download.
 *
 * The app's quality tiers map 1:1 to NetEase's `/song/url/v1` levels and to the custom-source tiers, so the
 * download asks for the user's **current** tier first (that is the preference the rest of the app already
 * expresses) and then walks **down** the list: a source or account may not have the top tier, and a lower-tier full
 * track is a better download than a failure. Tiers above the setting are not tried — downloading a Hi-Res file when
 * the user chose 极高 would spend data they did not ask for.
 */
object DownloadQualityPlan {

    /** Best → worst, the order NetEase and the ported providers use. */
    val TIERS: List<String> = listOf("hires", "lossless", "exhigh", "higher", "standard")

    const val DEFAULT_PREFERRED = "exhigh"

    /** The preferred tier first, then every tier below it. Unknown preferences fall back to [DEFAULT_PREFERRED]. */
    fun order(preferred: String): List<String> {
        val normalized = preferred.lowercase()
        val start = TIERS.indexOf(normalized).takeIf { it >= 0 } ?: TIERS.indexOf(DEFAULT_PREFERRED)
        return TIERS.drop(start)
    }

    /** User-facing tier label for the download notice ("正在下载「…」（无损）"). */
    fun qualityLabel(level: String): String = when (level.lowercase()) {
        "standard" -> "标准"
        "higher" -> "较高"
        "exhigh" -> "极高"
        "lossless" -> "无损"
        "hires" -> "Hi-Res"
        else -> level
    }
}
