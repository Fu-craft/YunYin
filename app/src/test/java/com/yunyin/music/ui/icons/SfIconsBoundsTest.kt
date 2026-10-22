package com.yunyin.music.ui.icons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar's icon-family contract, pinned by measuring the path data in `SfIcons.kt`.
 *
 * A Compose `ImageVector` has no runtime bounds API and the path data is authored as `PathData`, so
 * the contract is checked by parsing the source file the same way `tools/icon_bounds.py` does (that
 * script is the human-facing report; this test is the CI-enforced guard).
 *
 * The design contract:
 *  - every **outline** uses the 3.4–20.6 horizontal box (the 2×2 grid is square, so it uses that box
 *    on both axes; the heart/person/house use 3.9–20.6 for their vertical mass);
 *  - every **filled** variant uses exactly 2.95–21.05 in both axes, so selecting a slot adds weight
 *    without changing its size or position (the 4.10.1 house was 2.45–21.55 and read as a jump).
 *
 * The numbers below are the deliberate expected table: a redraw must update them, which is the
 * point — the two states cannot silently drift apart again.
 */
class SfIconsBoundsTest {

    private data class Bounds(val x0: Float, val x1: Float, val y0: Float, val y1: Float)

    private val contract: Map<String, Bounds> = linkedMapOf(
        "House" to Bounds(3.4f, 20.6f, 3.9f, 20.6f),
        "HouseFill" to Bounds(2.95f, 21.05f, 2.95f, 21.05f),
        "SquareGrid2x2" to Bounds(3.4f, 20.6f, 3.4f, 20.6f),
        "SquareGrid2x2Fill" to Bounds(2.95f, 21.05f, 2.95f, 21.05f),
        "PersonOutline" to Bounds(3.4f, 20.6f, 4.1f, 20.6f),
        "PersonFill" to Bounds(2.95f, 21.05f, 2.95f, 21.05f),
        "Heart" to Bounds(3.4f, 20.6f, 3.9f, 20.2f),
        "HeartFill" to Bounds(2.95f, 21.05f, 2.95f, 21.05f),
    )

    private val tolerance = 0.16f

    @Test
    fun `every bar glyph stays inside the family's documented box`() {
        val source = sourceFile().readText()
        contract.forEach { (name, expected) ->
            val actual = measure(source, name)
            assertEquals("$name x0", expected.x0, actual.x0, tolerance)
            assertEquals("$name x1", expected.x1, actual.x1, tolerance)
            assertEquals("$name y0", expected.y0, actual.y0, tolerance)
            assertEquals("$name y1", expected.y1, actual.y1, tolerance)
        }
    }

    @Test
    fun `all four filled marks share one box`() {
        val source = sourceFile().readText()
        listOf("HouseFill", "SquareGrid2x2Fill", "PersonFill", "HeartFill").forEach { name ->
            val bounds = measure(source, name)
            assertEquals("$name x0", 2.95f, bounds.x0, tolerance)
            assertEquals("$name x1", 21.05f, bounds.x1, tolerance)
            assertEquals("$name y0", 2.95f, bounds.y0, tolerance)
            assertEquals("$name y1", 21.05f, bounds.y1, tolerance)
        }
    }

    @Test
    fun `all four outlines share the grid's horizontal box`() {
        val source = sourceFile().readText()
        listOf("House", "SquareGrid2x2", "PersonOutline", "Heart").forEach { name ->
            val bounds = measure(source, name)
            assertEquals("$name x0", 3.4f, bounds.x0, tolerance)
            assertEquals("$name x1", 20.6f, bounds.x1, tolerance)
        }
    }

    // ---------------------------------------------------------------- source measurement

    private fun measure(source: String, name: String): Bounds {
        val start = source.indexOf("    val $name: ImageVector")
        assertTrue("$name is declared in SfIcons.kt", start >= 0)
        val next = DECLARATION.find(source, start + 1)
        val block = source.substring(start, next?.range?.first ?: source.length)

        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        fun add(x: Float, y: Float) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }

        CALL.findAll(block).forEach { match ->
            val values = match.groupValues[2].split(",").mapNotNull { part ->
                NUMBER.find(part.substringAfterLast('='))?.value?.toFloatOrNull()
            }
            when (match.groupValues[1]) {
                "moveTo", "lineTo", "curveTo", "quadTo" -> values.chunked(2).forEach {
                    if (it.size == 2) add(it[0], it[1])
                }
                "roundedRect" -> {
                    val x = values[0]
                    val y = values[1]
                    val size = values[2]
                    add(x, y)
                    add(x + size, y + size)
                }
            }
        }
        assertTrue("$name has drawing calls", x1 > 0f && y1 > 0f)
        return Bounds(x0, x1, y0, y1)
    }

    private fun sourceFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val roots = listOf(File(dir, "app/src/main"), File(dir, "src/main"))
            roots.forEach { root ->
                val file = File(root, "java/com/yunyin/music/ui/icons/SfIcons.kt")
                if (file.isFile) return file
            }
            dir = dir.parentFile
        }
        throw AssertionError("SfIcons.kt not found from ${File("").absolutePath}")
    }

    private companion object {
        val DECLARATION = Regex("""\n    val [A-Za-z0-9]+: ImageVector""")
        val CALL = Regex("""\b(moveTo|lineTo|curveTo|quadTo|roundedRect)\(([^)]*)\)""")
        val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    }
}
