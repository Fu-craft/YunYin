package com.yunyin.music.aura

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleMusicArtistLogoTest {
    @Test
    fun `parenthesized latin alias is searched before NetEase display name`() {
        assertEquals(
            listOf("MULA SAKEE", "皇昶枢（MULA SAKEE）"),
            AppleMusicArtistLogoManager.artistNameCandidates("皇昶枢（MULA SAKEE）"),
        )
    }

    @Test
    fun `plain artist name remains the only candidate`() {
        assertEquals(
            listOf("MULA SAKEE"),
            AppleMusicArtistLogoManager.artistNameCandidates("MULA SAKEE"),
        )
    }
}
