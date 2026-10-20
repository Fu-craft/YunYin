package com.yunyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the scroll-linked bar-title rule shared by the artist page and Settings. */
class ScrollLinkedTitleAlphaTest {

    @Test
    fun `the title is hidden while the header is on screen`() {
        assertEquals(0f, scrollLinkedTitleAlpha(0, 0, 0f, 120f), 0f)
        assertEquals(0f, scrollLinkedTitleAlpha(0, -20, 0f, 120f), 0f)
    }

    @Test
    fun `the title fades over the header height and clamps at full`() {
        assertEquals(0.5f, scrollLinkedTitleAlpha(0, 60, 0f, 120f), 1e-6f)
        assertEquals(1f, scrollLinkedTitleAlpha(0, 120, 0f, 120f), 0f)
        assertEquals(1f, scrollLinkedTitleAlpha(0, 500, 0f, 120f), 0f)
    }

    @Test
    fun `a second list item means the header has passed`() {
        assertEquals(1f, scrollLinkedTitleAlpha(1, 0, 0f, 120f), 0f)
    }

    @Test
    fun `an unmeasured header keeps the bar title hidden instead of flashing`() {
        assertEquals(0f, scrollLinkedTitleAlpha(0, 80, 0f, 0f), 0f)
    }
}
