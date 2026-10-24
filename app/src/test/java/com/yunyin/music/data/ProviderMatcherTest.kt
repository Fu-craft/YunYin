// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.ProviderMatcher
import com.yunyin.music.data.providers.SearchMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform matching and search merging.
 *
 * These two rules share this test class because they must agree: the user sees one merged list, and playback
 * toggles a NetEase track to the *other* row for the same recording. If the merge thought two rows were the same
 * song while the matcher did not, the list would hide a provider version the player would never find (or vice
 * versa). Both use [ProviderMatcher.normalize]/[ProviderMatcher.singerKey]/the ±5 s interval rule.
 */
class ProviderMatcherTest {

    // ------------------------------------------------------------------ matching

    @Test
    fun `an exact recording is preferred over a live version`() {
        val target = netease(id = 1, name = "晴天", singer = "周杰伦", album = "叶惠美", seconds = 269)
        val live = provider("晴天 (Live)", "周杰伦", "演唱会", 269)
        val studio = provider("晴天", "周杰伦", "叶惠美", 269)
        val match = ProviderMatcher.bestMatch(listOf(live, studio), target)
        assertEquals("晴天", match?.name)
    }

    @Test
    fun `a duration within five seconds matches, six does not`() {
        val target = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        assertEquals(
            "晴天",
            ProviderMatcher.bestMatch(listOf(provider("晴天", "周杰伦", "叶惠美", 274)), target)?.name,
        )
        assertNull(ProviderMatcher.bestMatch(listOf(provider("晴天", "周杰伦", "叶惠美", 275)), target))
    }

    @Test
    fun `a different song with the same duration is rejected`() {
        // This is the guard that stops the app from silently playing the wrong audio.
        val target = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        assertNull(ProviderMatcher.bestMatch(listOf(provider("七里香", "周杰伦", "七里香", 269)), target))
    }

    @Test
    fun `multi-artist strings compare after normalisation`() {
        val target = netease(1, "晴天", "周杰伦 / 林俊杰", "", 300)
        val candidate = provider("晴天", "林俊杰、周杰伦", "", 300)
        assertEquals("晴天", ProviderMatcher.bestMatch(listOf(candidate), target)?.name)
    }

    @Test
    fun `normalisation strips punctuation and brackets so titles compare`() {
        assertEquals("晴天", ProviderMatcher.normalize("《晴天》"))
        assertEquals("", ProviderMatcher.normalize("  "))
        assertEquals("abc", ProviderMatcher.normalize("A.B,C！"))
    }

    @Test
    fun `an empty candidate list is not a match`() {
        assertNull(ProviderMatcher.bestMatch(emptyList(), netease(1, "x", "y", "", 10)))
    }

    @Test
    fun `a cover of the same title by another artist is never matched`() {
        // This is the "the song plays but it is not 周杰伦's voice" failure: cover uploads sit next to the original
        // in search results and can have the same duration. Voice identity requires the artist to relate.
        val target = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        val cover = provider("晴天", "某翻唱歌手", "", 269)
        assertNull(ProviderMatcher.bestMatch(listOf(cover), target))
    }

    @Test
    fun `an artist that contains the target artist still matches`() {
        val target = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        val duet = provider("晴天", "周杰伦、林俊杰", "", 269)
        assertEquals("晴天", ProviderMatcher.bestMatch(listOf(duet), target)?.name)
    }

    @Test
    fun `a track with no artist accepts any related title`() {
        val target = netease(1, "晴天", "", "", 269)
        assertEquals(
            "晴天",
            ProviderMatcher.bestMatch(listOf(provider("晴天", "任意歌手", "", 269)), target)?.name,
        )
    }

    // ------------------------------------------------------------------ merging

    @Test
    fun `a provider duplicate of a NetEase row is not shown twice`() {
        val netease = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        val duplicate = provider("晴天", "周杰伦", "叶惠美", 269)
        val merged = SearchMerge.merge(listOf(netease), listOf(duplicate))
        assertEquals(1, merged.size)
        assertEquals(netease.id, merged[0].id)
    }

    @Test
    fun `a provider-only song is appended with its ref`() {
        val netease = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        val other = provider("QQ 独有", "某人", "某专辑", 200)
        val merged = SearchMerge.merge(listOf(netease), listOf(other))
        assertEquals(2, merged.size)
        assertEquals("tx", merged[1].providerRef?.provider)
    }

    @Test
    fun `the same title with a different length is a different recording`() {
        val netease = netease(1, "晴天", "周杰伦", "叶惠美", 269)
        val longer = provider("晴天", "周杰伦", "叶惠美", 320)
        assertEquals(2, SearchMerge.merge(listOf(netease), listOf(longer)).size)
    }

    @Test
    fun `paging appends only new rows`() {
        val pageOne = listOf(
            netease(1, "a", "s", "", 100),
            netease(2, "b", "s", "", 100),
        )
        val pageTwo = listOf(
            netease(2, "b", "s", "", 100),
            netease(3, "c", "s", "", 100),
        )
        val appended = SearchMerge.appendUnique(pageOne, pageTwo)
        assertEquals(listOf(1L, 2L, 3L), appended.map { it.id })
    }

    @Test
    fun `sameRecording is symmetric and id-based first`() {
        val a = netease(1, "x", "s", "", 100)
        val b = a.copy(id = 1)
        assertTrue(SearchMerge.sameRecording(a, b))
        assertTrue(SearchMerge.sameRecording(a, netease(2, "x", "s", "", 100)))
        assertTrue(!SearchMerge.sameRecording(a, netease(2, "x", "s", "", 200)))
    }

    // ------------------------------------------------------------------ fixtures

    private fun netease(id: Long, name: String, singer: String, album: String, seconds: Int): Track = Track(
        id = id,
        name = name,
        artists = singer.split(" / ").filter { it.isNotBlank() },
        albumName = album,
        albumId = 0L,
        coverUrl = null,
        durationMs = seconds * 1000L,
    )

    private fun provider(name: String, singer: String, album: String, seconds: Int): Track = Track(
        id = -seconds.toLong() - name.hashCode(),
        name = name,
        artists = singer.split(" / ").filter { it.isNotBlank() },
        albumName = album,
        albumId = 0L,
        coverUrl = null,
        durationMs = seconds * 1000L,
        providerRef = ProviderRef(
            provider = "tx",
            providerId = name,
            intervalSeconds = seconds,
        ),
    )
}
