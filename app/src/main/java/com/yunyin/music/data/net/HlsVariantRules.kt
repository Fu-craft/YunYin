// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import java.net.URI

/**
 * One video rendition advertised by an HLS master playlist.
 *
 * Mirrors the parser in LX-Music's `appleDynamicCover/hls.ts`: the Apple animated-artwork masters
 * list both H.264 (`avc1`) and HEVC (`hvc1`) renditions, and a player that simply takes the master
 * can end up on an HEVC variant that the device cannot decode — which shows up as a black cover that
 * never produces a first frame.
 */
data class HlsVariant(
    val uri: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
) {
    val maxEdge: Int get() = maxOf(width, height)
    val isAvc: Boolean get() = Regex("""avc1|avc3|mp4v""", RegexOption.IGNORE_CASE).containsMatchIn(codecs)
}

/** Pure rules for picking a rendition out of an HLS master playlist. */
object HlsVariantRules {

    private val STREAM_INF = Regex("""#EXT-X-STREAM-INF:(.*)$""")
    private val CODECS = Regex("""CODECS="([^"]*)"""")
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH = Regex("""[,:]BANDWIDTH=(\d+)""")

    /** Parses `#EXT-X-STREAM-INF` blocks; the URI is the next non-comment line. */
    fun parseMaster(playlist: String): List<HlsVariant> {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<HlsVariant>()
        var index = 0
        while (index < lines.size) {
            val match = STREAM_INF.matchEntire(lines[index])
            if (match == null) {
                index++
                continue
            }
            val attributes = match.groupValues[1]
            var uri: String? = null
            var next = index + 1
            while (next < lines.size) {
                val candidate = lines[next]
                if (candidate.isEmpty()) {
                    next++
                    continue
                }
                if (!candidate.startsWith("#")) uri = candidate
                break
            }
            if (uri != null) {
                val resolution = RESOLUTION.find(attributes)
                val width = resolution?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val height = resolution?.groupValues?.get(2)?.toIntOrNull() ?: 0
                variants += HlsVariant(
                    uri = uri,
                    codecs = CODECS.find(attributes)?.groupValues?.get(1).orEmpty(),
                    width = width,
                    height = height,
                    bandwidth = BANDWIDTH.find(attributes)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                )
            }
            index = next + 1
        }
        return variants
    }

    /**
     * Largest AVC rendition whose longest edge fits [maxEdge]; when every AVC rendition is larger,
     * the smallest one is used instead. HEVC-only masters fall back to the rendition closest to
     * [maxEdge] so a device with HEVC support still gets a picture.
     *
     * Within the chosen resolution the **cheapest** bitrate wins: the master offers several 1080
     * AVC rungs and taking the top one would stream ~10 Mbps for a cover that is on screen at a few
     * hundred dp.
     */
    fun pickBest(variants: List<HlsVariant>, maxEdge: Int, maxBitrate: Long = 6_000_000): HlsVariant? {
        if (variants.isEmpty()) return null
        val avc = variants.filter { it.isAvc && it.width > 0 && it.height > 0 }
        val avcAny = variants.filter { it.isAvc }
        if (avc.isNotEmpty()) {
            val within = avc.filter { it.maxEdge <= maxEdge }
            val pool = if (within.isNotEmpty()) within else avc
            val edge = if (within.isNotEmpty()) pool.maxOf { it.maxEdge } else pool.minOf { it.maxEdge }
            val atEdge = pool.filter { it.maxEdge == edge }
            val affordable = atEdge.filter { it.bandwidth in 1..maxBitrate }
            return (affordable.ifEmpty { atEdge }).minByOrNull { it.bandwidth } ?: atEdge.first()
        }
        if (avcAny.isNotEmpty()) return avcAny.first()
        val sized = variants.filter { it.width > 0 && it.height > 0 }
        if (sized.isEmpty()) return null
        return sized.minByOrNull { kotlin.math.abs(it.maxEdge - maxEdge) } ?: sized.first()
    }

    /** Resolves a (possibly relative) rendition URI against the playlist URL. */
    fun resolveUri(baseUrl: String, uri: String): String = runCatching {
        URI(baseUrl).resolve(uri).toString()
    }.getOrDefault(uri)

    /** True when the body is a master playlist rather than a media playlist. */
    fun isMaster(playlist: String): Boolean = playlist.contains("#EXT-X-STREAM-INF")
}
