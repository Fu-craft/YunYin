package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Artist-line segments: every name gets its own (non-empty) range and separators get none.
 *
 * The bug: separators were built as "<name> / " and subtracted from the next cut, collapsing every
 * non-final span to an empty range — "多人合作只能点到最后一个人".
 */
class ArtistSegmentsTest {

    @Test
    fun `each artist gets its own non-empty range`() {
        val line = "A / B / C"
        val segments = ArtistSegments.segments(line, listOf("A", "B", "C"))
        assertEquals(3, segments.size)
        assertEquals("A", line.substring(segments[0].range))
        assertEquals("B", line.substring(segments[1].range))
        assertEquals("C", line.substring(segments[2].range))
        assertTrue(segments.all { !it.range.isEmpty() })
    }

    @Test
    fun `separators are not part of any range`() {
        val line = "A / B"
        val segments = ArtistSegments.segments(line, listOf("A", "B"))
        val covered = segments.flatMap { it.range.toList() }.toSet()
        val separatorOffsets = line.indices.filter { line[it] == '/' || (line[it] == ' ' && it > 0 && line[it - 1] == '/') }
        assertTrue(separatorOffsets.none { it in covered })
    }

    @Test
    fun `one-character names and repeated names still resolve`() {
        val line = "G / G"
        val segments = ArtistSegments.segments(line, listOf("G", "G"))
        assertEquals("G", line.substring(segments[0].range))
        assertEquals("G", line.substring(segments[1].range))
        assertTrue(segments[0].range != segments[1].range)
    }

    @Test
    fun `a single artist spans the whole line`() {
        val line = "周杰伦"
        val segments = ArtistSegments.segments(line, listOf("周杰伦"))
        assertEquals(1, segments.size)
        assertEquals(0 until line.length, segments[0].range)
    }
}