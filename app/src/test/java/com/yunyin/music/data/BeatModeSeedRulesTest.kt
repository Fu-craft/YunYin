// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.net.BeatModeRules
import com.yunyin.music.data.net.BeatModeSeedRules
import com.yunyin.music.data.net.BeatSeedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Priority of 心动模式's seed when the user taps it with nothing playing: current > liked > recommend >
 * error. Pinned with fakes because the user-visible behaviour ("it just starts playing") depends on the
 * order and on refusing id-only rows.
 */
class BeatModeSeedRulesTest {

    private fun track(id: Long, name: String = "t$id", artists: List<String> = listOf("a")) = Track(
        id = id, name = name, artists = artists, albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )

    @Test
    fun `the current track wins when something is playing`() {
        val seed = BeatModeSeedRules.choose(
            current = track(7),
            liked = listOf(track(1)),
            recommended = listOf(track(2)),
        )
        assertEquals(7L, seed?.track?.id)
        assertEquals(BeatSeedPath.Current, seed?.path)
    }

    @Test
    fun `with nothing playing the first liked track seeds the mode`() {
        val seed = BeatModeSeedRules.choose(
            current = null,
            liked = listOf(track(11), track(12)),
            recommended = listOf(track(21)),
        )
        assertEquals(11L, seed?.track?.id)
        assertEquals(BeatSeedPath.Liked, seed?.path)
    }

    @Test
    fun `an empty liked list falls back to daily recommendations`() {
        val seed = BeatModeSeedRules.choose(
            current = null,
            liked = emptyList(),
            recommended = listOf(track(21), track(22)),
        )
        assertEquals(21L, seed?.track?.id)
        assertEquals(BeatSeedPath.Recommend, seed?.path)
    }

    @Test
    fun `no current, no likes and no recommendations is an error rather than a silent no-op`() {
        assertNull(BeatModeSeedRules.choose(null, emptyList(), emptyList()))
        // The host must report this specific message: the bug report's "no reaction at all" must never
        // be reproducible as silence when there is genuinely no seed.
        assertEquals("没有可用的歌曲，无法开启心动模式", BeatModeRules.NO_SOURCE_MESSAGE)
    }

    @Test
    fun `an id-only row is never chosen as a seed`() {
        val blank = track(9, name = "", artists = emptyList())
        // The blank current/liked entries are skipped in favour of the next usable candidate.
        val seed = BeatModeSeedRules.choose(
            current = blank,
            liked = listOf(track(9, name = " ", artists = listOf("")), track(3)),
            recommended = emptyList(),
        )
        assertEquals(3L, seed?.track?.id)
        assertEquals(BeatSeedPath.Liked, seed?.path)

        // If everything is id-only, that is still the no-seed error, not a blank queue.
        assertNull(BeatModeSeedRules.choose(blank, listOf(blank), listOf(blank)))
    }
}
