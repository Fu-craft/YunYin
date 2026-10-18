package com.yunyin.music.ui.screens

import com.yunyin.music.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure artist-page rules: bio clamping, paging offsets, duplicate-free appends, album years. */
class ArtistUiRulesTest {

    private fun track(id: Long) = Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )

    @Test
    fun `a long bio is clamped and marked with an ellipsis`() {
        val bio = "x".repeat(665)
        val clamped = ArtistUiRules.clampBio(bio)
        assertTrue(clamped.length <= ArtistUiRules.BIO_PREVIEW_CHARS + 1)
        assertTrue(clamped.endsWith("…"))
        assertEquals("短简介", ArtistUiRules.clampBio(" 短简介 "))
    }

    @Test
    fun `next offset only exists while more is true`() {
        assertEquals(30, ArtistUiRules.nextOffset(0, 30, more = true))
        assertEquals(60, ArtistUiRules.nextOffset(30, 30, more = true))
        assertNull(ArtistUiRules.nextOffset(60, 30, more = false))
    }

    @Test
    fun `appending pages never duplicates a track`() {
        val merged = ArtistUiRules.appendUnique(listOf(track(1), track(2)), listOf(track(2), track(3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `album year comes from publish time and absent time is null`() {
        assertEquals("2023", ArtistUiRules.yearOf(1_700_000_000_000L))
        assertNull(ArtistUiRules.yearOf(0L))
    }
}