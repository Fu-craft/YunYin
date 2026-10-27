// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsVariantRulesTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-STREAM-INF:BANDWIDTH=2220179,CODECS="avc1.64001f",RESOLUTION=768x768
        https://cdn/v/768_avc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1287056,CODECS="hvc1.2.20000000.L123.B0",RESOLUTION=768x768
        https://cdn/v/768_hevc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=294167,CODECS="avc1.64001f",RESOLUTION=360x360
        https://cdn/v/360_avc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=520323,CODECS="avc1.64001f",RESOLUTION=456x456
        https://cdn/v/456_avc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9703438,CODECS="hvc1.2.20000000.H150.B0",RESOLUTION=2160x2160
        https://cdn/v/2160_hevc.m3u8
    """.trimIndent()

    @Test
    fun parsesEveryVariant() {
        val variants = HlsVariantRules.parseMaster(master)
        assertEquals(5, variants.size)
        assertEquals("https://cdn/v/768_avc.m3u8", variants.first().uri)
        assertEquals(768, variants.first().width)
        assertTrue(variants.first().isAvc)
        assertTrue(variants[1].codecs.startsWith("hvc1"))
    }

    @Test
    fun picksLargestAvcWithinTheEdgeCap() {
        val picked = HlsVariantRules.pickBest(HlsVariantRules.parseMaster(master), maxEdge = 640)
        assertEquals("https://cdn/v/456_avc.m3u8", picked?.uri)
    }

    @Test
    fun prefersSmallerAvcWhenEveryAvcIsTooLarge() {
        val small = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="avc1.640020",RESOLUTION=1080x1080
            https://cdn/v/1080_avc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.64001f",RESOLUTION=768x768
            https://cdn/v/768_avc.m3u8
        """.trimIndent()
        assertEquals("https://cdn/v/768_avc.m3u8", HlsVariantRules.pickBest(HlsVariantRules.parseMaster(small), 640)?.uri)
    }

    @Test
    fun fallsBackToHevcOnlyMasters() {
        val hevcOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="hvc1.1",RESOLUTION=768x768
            https://cdn/v/768_hevc.m3u8
        """.trimIndent()
        assertEquals("https://cdn/v/768_hevc.m3u8", HlsVariantRules.pickBest(HlsVariantRules.parseMaster(hevcOnly), 640)?.uri)
    }

    @Test
    fun mediaPlaylistIsNotAMaster() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg1.m4s\n"
        assertEquals(false, HlsVariantRules.isMaster(media))
        assertNull(HlsVariantRules.pickBest(HlsVariantRules.parseMaster(media), 640))
    }

    @Test
    fun resolvesRelativeRenditionUris() {
        assertEquals(
            "https://cdn/v/456_avc.m3u8",
            HlsVariantRules.resolveUri("https://cdn/master.m3u8", "v/456_avc.m3u8"),
        )
    }
}
