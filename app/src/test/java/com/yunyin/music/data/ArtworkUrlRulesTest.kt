// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The artwork URL rule, pinned with the real URL shapes the catalogue returns.
 *
 * The bug this guards: `/top/playlist` returns a plain `.jpg` for 全部歌单 but an image-processing
 * chain (`?imageView=1&thumbnail=800y800&…&thumbnail=140y140`) for the other categories. The old
 * "leave any URL that already has a query untouched" rule therefore asked the CDN for the 140×140
 * thumbnail on category pages, which the card upscaled into a blurry tile.
 */
class ArtworkUrlRulesTest {

    @Test
    fun `a plain netease url gets the requested size`() {
        assertEquals(
            "http://p2.music.126.net/x-UpYAXLd0C9VqZz087z_w==/109951173781501220.jpg?param=600y600",
            ArtworkUrlRules.sized(
                "http://p2.music.126.net/x-UpYAXLd0C9VqZz087z_w==/109951173781501220.jpg",
                600,
            ),
        )
    }

    @Test
    fun `an existing param is replaced, not kept`() {
        assertEquals(
            "https://p1.music.126.net/cover.jpg?param=600y600",
            ArtworkUrlRules.sized("https://p1.music.126.net/cover.jpg?param=140y140", 600),
        )
    }

    @Test
    fun `a category image-processing chain is replaced with the requested size`() {
        val categoryUrl =
            "http://p1.music.126.net/0ejs7kUx4QW3DmCD4gUQ1Q==/109951172516531617.jpg" +
                "?imageView=1&thumbnail=800y800&enlarge=1%7CimageView=1&watermark&type=1" +
                "&image=abc&dx=0&dy=0%7CimageView=1&thumbnail=140y140&"
        val resolved = ArtworkUrlRules.sized(categoryUrl, 600)
        assertEquals(
            "http://p1.music.126.net/0ejs7kUx4QW3DmCD4gUQ1Q==/109951172516531617.jpg?param=600y600",
            resolved,
        )
        // The API's own thumbnail chain must not survive: no 140y140 thumbnail reaches the CDN.
        assertEquals(false, resolved!!.contains("thumbnail"))
    }

    @Test
    fun `https netease hosts are handled like http`() {
        assertEquals(
            "https://p2.music.126.net/abc.jpg?param=400y400",
            ArtworkUrlRules.sized("https://p2.music.126.net/abc.jpg", 400),
        )
    }

    @Test
    fun `non-netease urls keep their own signed query`() {
        val signed = "https://cdn.example.com/a.jpg?token=abc&expires=1"
        assertEquals(signed, ArtworkUrlRules.sized(signed, 600))
    }

    @Test
    fun `non-netease urls without a query still get a size`() {
        assertEquals(
            "https://cdn.example.com/a.jpg?param=600y600",
            ArtworkUrlRules.sized("https://cdn.example.com/a.jpg", 600),
        )
    }

    @Test
    fun `blank input and invalid sizes resolve to null`() {
        assertNull(ArtworkUrlRules.sized(null, 600))
        assertNull(ArtworkUrlRules.sized("   ", 600))
        assertNull(ArtworkUrlRules.sized("https://p1.music.126.net/a.jpg", 0))
    }
}
