// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
package com.yunyin.music.data

import com.yunyin.music.ui.background.AmllMesh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins AMLL mesh generation determinism and the 24-subdivision contract. */
class AmllMeshTest {

    private val presets = """
        preset 3 3
        0 0 -1 -1 0 0 1 1
        1 0 0 -1 0 0 1 1
        2 0 1 -1 0 0 1 1
        0 1 -1 0 0 0 1 1
        1 1 0 0 0 0 1 1
        2 1 1 0 0 0 1 1
        0 2 -1 1 0 0 1 1
        1 2 0 1 0 0 1 1
        2 2 1 1 0 0 1 1
    """.trimIndent()

    @Test
    fun `mesh generation is deterministic for a seed`() {
        val a = AmllMesh.create(42L, presets)
        val b = AmllMesh.create(42L, presets)
        assertArrayEquals(a.normalizedPoints, b.normalizedPoints, 0f)
        assertArrayEquals(a.textureCoordinates, b.textureCoordinates, 0f)
        assertArrayEquals(a.indices, b.indices)
        assertTrue(a.normalizedPoints.isNotEmpty())
        assertTrue(a.textureCoordinates.isNotEmpty())
        assertTrue(a.indices.isNotEmpty())
    }

    @Test
    fun `AMLL mesh keeps 24 subdivisions and matching index count`() {
        assertEquals(24, AmllMesh.SUBDIVISIONS)
        val data = AmllMesh.create(7L, presets)
        // Generator and presets both produce a square grid; each square is two triangles.
        val vertices = data.normalizedPoints.size / 2
        val verticesPerAxis = kotlin.math.sqrt(vertices.toDouble()).toInt()
        assertEquals(verticesPerAxis * verticesPerAxis, vertices)
        val expectedIndices = (verticesPerAxis - 1) * (verticesPerAxis - 1) * 6
        assertEquals(expectedIndices, data.indices.size)
    }
}
