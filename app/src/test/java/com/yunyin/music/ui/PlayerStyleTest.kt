package com.yunyin.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PlayerStyle.from` decides which player a returning user sees, and it is read on every launch from a
 * stored string.
 *
 * The failure it guards against is a silent one: if the stored value and the enum's `level` ever disagree —
 * a rename, a reordering that switched to ordinals, a typo — the lookup fails, and *what the user chose* is
 * replaced by the default with nothing on screen to explain it. Falling back is right; falling back for
 * anything unrecognised (rather than crashing or drawing nothing) is the behaviour pinned here.
 */
class PlayerStyleTest {

    @Test
    fun `each style round-trips through its stored level`() {
        PlayerStyle.entries.forEach { style ->
            assertEquals(style, PlayerStyle.from(style.level))
        }
    }

    @Test
    fun `an unknown level falls back to the classic cover`() {
        assertEquals(PlayerStyle.Classic, PlayerStyle.from("something-else"))
    }

    @Test
    fun `a missing level falls back to the classic cover`() {
        // A fresh install has never written the key.
        assertEquals(PlayerStyle.Classic, PlayerStyle.from(null))
        assertEquals(PlayerStyle.Classic, PlayerStyle.from(""))
    }

    @Test
    fun `the stored levels are stable, lowercase identifiers`() {
        // These strings are in users' preferences already; changing them silently resets the choice.
        assertEquals("classic", PlayerStyle.Classic.level)
        assertEquals("vinyl", PlayerStyle.Vinyl.level)
    }

    @Test
    fun `every style has a label and a description to show in the picker`() {
        PlayerStyle.entries.forEach { style ->
            assertEquals(true, style.label.isNotBlank())
            assertEquals(true, style.detail.isNotBlank())
        }
    }
}
