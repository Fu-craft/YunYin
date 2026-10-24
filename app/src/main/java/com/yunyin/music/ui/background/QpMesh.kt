// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// Portions ported from QPlayer (Apache-2.0 wrapper) / AMLL Core (AGPL-3.0-only),
// © AMLL Contributors. Source: AMLLMeshGradient.java and meshgradient.sksl.

package com.yunyin.music.ui.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Port of AMLLMeshGradient: presets, generator and bicubic Hermite patch evaluation. */
internal object AmllMesh {
    const val SUBDIVISIONS = 24
    const val PRESET_SLOTS = 5

    class Data(
        val normalizedPoints: FloatArray,
        val textureCoordinates: FloatArray,
        val indices: ShortArray,
    ) {
        fun points(w: Float, h: Float): FloatArray {
            val aspect = w / h
            val out = FloatArray(normalizedPoints.size)
            var i = 0
            while (i < normalizedPoints.size) {
                val x = normalizedPoints[i]
                var y = -normalizedPoints[i + 1]
                if (aspect > 1f) y *= aspect else y /= 1f
                if (aspect <= 1f) {
                    // Same AMLL aspect rule as QPlayer: x is divided when portrait.
                    out[i] = (x / aspect * 0.5f + 0.5f) * w
                } else {
                    out[i] = (x * 0.5f + 0.5f) * w
                }
                out[i + 1] = (y * 0.5f + 0.5f) * h
                i += 2
            }
            return out
        }
    }

    private data class Config(
        val cx: Int, val cy: Int, val x: Double, val y: Double,
        val ur: Double, val vr: Double, val up: Double, val vp: Double,
    )

    private data class Preset(val width: Int, val height: Int, val points: List<Config>)

    private data class ControlPoint(
        var x: Double = 0.0, var y: Double = 0.0,
        var ux: Double = 0.0, var uy: Double = 0.0,
        var vx: Double = 0.0, var vy: Double = 0.0,
    )

    fun create(seed: Long, presetsText: String): Data {
        val presets = parsePresets(presetsText)
        val random = Random(seed xor 0x9E3779B97F4A7C15uL.toLong())
        val chosen = if (random.nextDouble() > 0.8) generate(6, 6, random)
        else presets[random.nextInt(presets.size)]
        return build(chosen)
    }

    private fun parsePresets(text: String): List<Preset> {
        val result = ArrayList<Preset>()
        var width = 0
        var height = 0
        var points: MutableList<Config>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val values = line.split(Regex("\\s+"))
            if (values[0] == "preset") {
                points?.let { result.add(Preset(width, height, it.toList())) }
                width = values[1].toInt()
                height = values[2].toInt()
                points = ArrayList(width * height)
            } else if (points != null) {
                val ur = if (values.size > 4) values[4].toDouble() else 0.0
                val vr = if (values.size > 5) values[5].toDouble() else 0.0
                val up = if (values.size > 6) values[6].toDouble() else 1.0
                val vp = if (values.size > 7) values[7].toDouble() else 1.0
                points.add(
                    Config(
                        values[0].toInt(), values[1].toInt(),
                        values[2].toDouble(), values[3].toDouble(), ur, vr, up, vp,
                    ),
                )
            }
        }
        points?.let { result.add(Preset(width, height, it.toList())) }
        check(result.isNotEmpty()) { "No AMLL mesh presets loaded" }
        return result
    }

    private fun build(preset: Preset): Data {
        val cp = Array(preset.height) { arrayOfNulls<ControlPoint>(preset.width) }
        val uPower = 2.0 / (preset.width - 1)
        val vPower = 2.0 / (preset.height - 1)
        preset.points.forEach { conf ->
            val p = ControlPoint()
            p.x = conf.x
            p.y = conf.y
            val ur = Math.toRadians(conf.ur)
            val vr = Math.toRadians(conf.vr)
            val us = uPower * conf.up
            val vs = vPower * conf.vp
            p.ux = kotlin.math.cos(ur) * us
            p.uy = kotlin.math.sin(ur) * us
            p.vx = -kotlin.math.sin(vr) * vs
            p.vy = kotlin.math.cos(vr) * vs
            cp[conf.cy][conf.cx] = p
        }
        @Suppress("UNCHECKED_CAST")
        val grid = cp as Array<Array<ControlPoint>>
        val vertexW = (preset.height - 1) * SUBDIVISIONS
        val vertexH = (preset.width - 1) * SUBDIVISIONS
        val positions = FloatArray(vertexW * vertexH * 2)
        val texCoords = FloatArray(positions.size)
        val invSubDivM1 = 1.0 / (SUBDIVISIONS - 1)
        val invTW = 1.0 / ((SUBDIVISIONS - 1) * (preset.height - 1))
        val invTH = 1.0 / ((SUBDIVISIONS - 1) * (preset.width - 1))
        for (x in 0 until preset.width - 1) {
            for (y in 0 until preset.height - 1) {
                val sx = x / (preset.width - 1).toDouble()
                val sy = y / (preset.height - 1).toDouble()
                val baseVX = y * SUBDIVISIONS
                val baseVY = x * SUBDIVISIONS
                for (u in 0 until SUBDIVISIONS) {
                    val fu = u * invSubDivM1
                    val vx = baseVX + u
                    for (v in 0 until SUBDIVISIONS) {
                        val fv = v * invSubDivM1
                        val vy = baseVY + v
                        val index = (vx + vy * vertexW) * 2
                        val pos = evalPatch(grid, x, y, fv, fu)
                        positions[index] = pos[0].toFloat()
                        positions[index + 1] = pos[1].toFloat()
                        // Normalized UV (QPlayer stores 0..32; shader divides by 32).
                        texCoords[index] = (sx + v * invTH).toFloat()
                        texCoords[index + 1] = (1.0 - sy - u * invTW).toFloat()
                    }
                }
            }
        }
        val indices = ShortArray((vertexW - 1) * (vertexH - 1) * 6)
        var offset = 0
        for (y in 0 until vertexH - 1) {
            for (x in 0 until vertexW - 1) {
                val a = y * vertexW + x
                val b = a + 1
                val c = a + vertexW
                val d = c + 1
                indices[offset++] = a.toShort()
                indices[offset++] = b.toShort()
                indices[offset++] = c.toShort()
                indices[offset++] = b.toShort()
                indices[offset++] = d.toShort()
                indices[offset++] = c.toShort()
            }
        }
        return Data(positions, texCoords, indices)
    }

    private fun evalPatch(cp: Array<Array<ControlPoint>>, x: Int, y: Int, u: Double, v: Double): DoubleArray {
        val p00 = cp[y][x]
        val p10 = cp[y][x + 1]
        val p01 = cp[y + 1][x]
        val p11 = cp[y + 1][x + 1]
        val hu0 = 2 * u * u * u - 3 * u * u + 1
        val hu1 = u * u * u - 2 * u * u + u
        val hu2 = -2 * u * u * u + 3 * u * u
        val hu3 = u * u * u - u * u
        val hv0 = 2 * v * v * v - 3 * v * v + 1
        val hv1 = v * v * v - 2 * v * v + v
        val hv2 = -2 * v * v * v + 3 * v * v
        val hv3 = v * v * v - v * v
        val out = DoubleArray(2)
        for (axis in 0..1) {
            fun a(p: ControlPoint) = if (axis == 0) p.x else p.y
            fun uu(p: ControlPoint) = if (axis == 0) p.ux else p.uy
            fun vv(p: ControlPoint) = if (axis == 0) p.vx else p.vy
            val q0 = hu0 * a(p00) + hu1 * uu(p00) + hu2 * a(p10) + hu3 * uu(p10)
            val q1 = hu0 * a(p01) + hu1 * uu(p01) + hu2 * a(p11) + hu3 * uu(p11)
            val r0 = hu0 * vv(p00) + hu2 * vv(p10)
            val r1 = hu0 * vv(p01) + hu2 * vv(p11)
            out[axis] = hv0 * q0 + hv1 * r0 + hv2 * q1 + hv3 * r1
        }
        return out
    }

    private fun generate(width: Int, height: Int, random: Random): Preset {
        val variationFraction = range(random, 0.4, 0.6)
        val normalOffset = range(random, 0.3, 0.6)
        val blendFactor = 0.8
        val smoothIterations = floor(range(random, 3.0, 5.0)).toInt()
        val smoothFactor = range(random, 0.2, 0.3)
        val smoothModifier = range(random, -0.1, -0.05)
        val conf = ArrayList<Config>(width * height)
        val dx = 2.0 / (width - 1)
        val dy = 2.0 / (height - 1)
        for (j in 0 until height) {
            for (i in 0 until width) {
                val baseX = i / (width - 1).toDouble() * 2 - 1
                val baseY = j / (height - 1).toDouble() * 2 - 1
                val border = i == 0 || i == width - 1 || j == 0 || j == height - 1
                var x = baseX + if (border) 0.0 else range(random, -variationFraction * dx, variationFraction * dx)
                var y = baseY + if (border) 0.0 else range(random, -variationFraction * dy, variationFraction * dy)
                val ur = if (border) 0.0 else range(random, -60.0, 60.0)
                val vr = if (border) 0.0 else range(random, -60.0, 60.0)
                val up = if (border) 1.0 else range(random, 0.8, 1.2)
                val vp = if (border) 1.0 else range(random, 0.8, 1.2)
                if (!border) {
                    val un = (baseX + 1) / 2
                    val vn = (baseY + 1) / 2
                    val gradient = noiseGradient(un, vn)
                    val distance = min(min(un, 1 - un), min(vn, 1 - vn))
                    val weight = smoothstep(distance)
                    x += gradient[0] * normalOffset * weight * blendFactor
                    y += gradient[1] * normalOffset * weight * blendFactor
                }
                conf.add(Config(i, j, x, y, ur, vr, up, vp))
            }
        }
        smoothify(conf, width, height, smoothIterations, smoothFactor, smoothModifier)
        return Preset(width, height, conf)
    }

    private fun smoothify(conf: MutableList<Config>, w: Int, h: Int, iterations: Int, factor: Double, modifier: Double) {
        var grid = Array(h) { j -> Array(w) { i -> conf[j * w + i] } }
        val kernel = arrayOf(intArrayOf(1, 2, 1), intArrayOf(2, 4, 2), intArrayOf(1, 2, 1))
        var f = factor
        repeat(iterations) {
            val next = Array(h) { j -> Array(w) { i -> grid[j][i] } }
            for (j in 0 until h) for (i in 0 until w) {
                val current = grid[j][i]
                if (i == 0 || i == w - 1 || j == 0 || j == h - 1) {
                    next[j][i] = current
                    continue
                }
                val sums = DoubleArray(6)
                for (dj in -1..1) for (di in -1..1) {
                    val weight = kernel[dj + 1][di + 1]
                    val n = grid[j + dj][i + di]
                    sums[0] += n.x * weight; sums[1] += n.y * weight
                    sums[2] += n.ur * weight; sums[3] += n.vr * weight
                    sums[4] += n.up * weight; sums[5] += n.vp * weight
                }
                next[j][i] = Config(
                    i, j,
                    mix(current.x, sums[0] / 16, f), mix(current.y, sums[1] / 16, f),
                    mix(current.ur, sums[2] / 16, f), mix(current.vr, sums[3] / 16, f),
                    mix(current.up, sums[4] / 16, f), mix(current.vp, sums[5] / 16, f),
                )
            }
            grid = next
            f = clamp01(f + modifier)
        }
        conf.clear()
        for (j in 0 until h) conf.addAll(grid[j])
    }

    private fun noiseGradient(x: Double, y: Double): DoubleArray {
        val epsilon = 0.001
        val dx = (smoothNoise(x + epsilon, y) - smoothNoise(x - epsilon, y)) / (2 * epsilon)
        val dy = (smoothNoise(x, y + epsilon) - smoothNoise(x, y - epsilon)) / (2 * epsilon)
        val length = kotlin.math.sqrt(dx * dx + dy * dy).let { if (it == 0.0) 1.0 else it }
        return doubleArrayOf(dx / length, dy / length)
    }

    private fun smoothNoise(x: Double, y: Double): Double {
        val x0 = floor(x); val y0 = floor(y)
        val xf = x - x0; val yf = y - y0
        val u = xf * xf * (3 - 2 * xf)
        val v = yf * yf * (3 - 2 * yf)
        val nx0 = mix(noise(x0, y0), noise(x0 + 1, y0), u)
        val nx1 = mix(noise(x0, y0 + 1), noise(x0 + 1, y0 + 1), u)
        return mix(nx0, nx1, v)
    }

    private fun noise(x: Double, y: Double): Double =
        fract(sin(x * 12.9898 + y * 78.233) * 43758.5453)

    private fun smoothstep(x: Double): Double { val t = clamp01(x); return t * t * (3 - 2 * t) }
    private fun range(random: Random, min: Double, max: Double) = random.nextDouble() * (max - min) + min
    private fun mix(a: Double, b: Double, t: Double) = a * (1 - t) + b * t
    private fun clamp01(x: Double) = max(0.0, min(1.0, x))
    private fun fract(x: Double) = x - floor(x)
}

/** Native-resolution UV lookup rasterized from the AMLL mesh; one fragment pass samples it. */
internal object QpMeshLookup {
    fun presetsText(context: android.content.Context): String =
        context.assets.open("qplayer_mesh_presets.txt").bufferedReader().use { it.readText() }

    fun build(context: android.content.Context, trackId: Long, width: Int, height: Int): Bitmap? = runCatching {
        if (width <= 0 || height <= 0) return@runCatching null
        val mesh = AmllMesh.create(trackId, presetsText(context))
        val points = mesh.points(width.toFloat(), height.toFloat())
        val tex = mesh.textureCoordinates
        val pixels = IntArray(width * height)
        // Identity fallback keeps uncovered pixels stable.
        for (y in 0 until height) for (x in 0 until width) {
            val u = x / (width - 1f).coerceAtLeast(1f)
            val v = y / (height - 1f).coerceAtLeast(1f)
            pixels[y * width + x] = pack(u, v)
        }
        val idx = mesh.indices
        var tri = 0
        while (tri + 2 < idx.size) {
            val ia = idx[tri].toInt() and 0xFFFF
            val ib = idx[tri + 1].toInt() and 0xFFFF
            val ic = idx[tri + 2].toInt() and 0xFFFF
            tri += 3
            val ax = points[ia * 2]; val ay = points[ia * 2 + 1]
            val bx = points[ib * 2]; val by = points[ib * 2 + 1]
            val cx = points[ic * 2]; val cy = points[ic * 2 + 1]
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-5f) continue
            val minX = max(0, floor(min(ax, min(bx, cx))).toInt())
            val maxX = min(width - 1, kotlin.math.ceil(max(ax, max(bx, cx))).toInt())
            val minY = max(0, floor(min(ay, min(by, cy))).toInt())
            val maxY = min(height - 1, kotlin.math.ceil(max(ay, max(by, cy))).toInt())
            val ua = tex[ia * 2]; val va = tex[ia * 2 + 1]
            val ub = tex[ib * 2]; val vb = tex[ib * 2 + 1]
            val uc = tex[ic * 2]; val vc = tex[ic * 2 + 1]
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
                val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
                val w2 = 1f - w0 - w1
                if (w0 < -0.001f || w1 < -0.001f || w2 < -0.001f) continue
                pixels[y * width + x] = pack(
                    (ua * w0 + ub * w1 + uc * w2).coerceIn(0f, 1f),
                    (va * w0 + vb * w1 + vc * w2).coerceIn(0f, 1f),
                )
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()

    private fun pack(u: Float, v: Float): Int =
        (0xFF shl 24) or (u.mul255() shl 16) or (v.mul255() shl 8) or 0xFF

    private fun Float.mul255(): Int = (this * 255f).roundToInt().coerceIn(0, 255)
}

/** Mesh Gradient: native UV lookup + one AGSL fragment pass. */
@Composable
internal fun QpMeshBackground(
    cover: ImageBitmap?,
    trackId: Long,
    palette: DynamicBackgroundPalette,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    staticMotion: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val painter = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else runCatching {
            QpMeshPainter(context)
        }.getOrNull()
    }
    if (painter == null) {
        HyperBackground(palette, true, modifier, dim, paused, false, staticMotion)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx().roundToInt() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.toPx().roundToInt() }.coerceAtLeast(1)
        val prepared by produceState<QpMeshPrepared?>(null, cover, trackId, width, height) {
            value = if (cover == null) null else withContext(Dispatchers.Default) {
                val texture = QpCover.prepare(cover, adjusted = true, trackId = trackId) ?: return@withContext null
                val lookup = QpMeshLookup.build(context, trackId, width, height) ?: return@withContext null
                QpMeshPrepared(texture, lookup)
            }
        }
        var current by remember { mutableStateOf<QpMeshPrepared?>(null) }
        var previous by remember { mutableStateOf<QpMeshPrepared?>(null) }
        var frameTick by remember { mutableIntStateOf(0) }
        val latestPaused by rememberUpdatedState(paused)
        val latestStatic by rememberUpdatedState(staticMotion)
        val latestDim by rememberUpdatedState(dim)
        val animatorsOn = remember {
            runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        }
        LaunchedEffect(prepared, painter) {
            val next = prepared ?: return@LaunchedEffect
            val prior = current
            previous = prior ?: next
            current = next
            painter.setCovers(next.texture, prior?.texture ?: next.texture)
            painter.setLookups(next.lookup, prior?.lookup ?: next.lookup)
            val fadeStartNs = if (prior != null && !staticMotion) System.nanoTime() else 0L
            val startNs = System.nanoTime()
            frameTick++
            var nextFrameNs = 0L
            while (isActive) {
                var advanced = false
                withFrameNanos { ns ->
                    if (latestPaused) return@withFrameNanos
                    if (nextFrameNs != 0L && ns < nextFrameNs) return@withFrameNanos
                    nextFrameNs = if (ns - nextFrameNs > BackgroundStyleRules.FRAME_INTERVAL_NS * 2L) {
                        ns + BackgroundStyleRules.FRAME_INTERVAL_NS
                    } else {
                        nextFrameNs + BackgroundStyleRules.FRAME_INTERVAL_NS
                    }
                    val motion = animatorsOn && !latestStatic
                    painter.setTime(if (motion) ((ns - startNs) / 1_000_000_000f) % 3600f else 0f)
                    val fade = if (fadeStartNs == 0L) 1f else {
                        ((ns - fadeStartNs).coerceAtLeast(0L) / 1_000_000_000f / QpRules.FADE_SECONDS)
                            .coerceIn(0f, 1f)
                    }
                    painter.setFade(fade)
                    advanced = motion || fade < 1f
                }
                if (advanced) frameTick++
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frameTick
            if (current == null) return@Canvas
            painter.setResolution(size.width, size.height)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, painter.paint)
            }
            val darken = latestDim.coerceIn(0f, 1f)
            if (darken > 0f) drawRect(Color.Black.copy(alpha = darken))
        }
    }
}

private class QpMeshPrepared(val texture: QpPreparedCover, val lookup: Bitmap)

private class QpMeshPainter(context: android.content.Context) {
    val shader: RuntimeShader
    val paint: Paint

    init {
        val source = context.assets.open("shaders/qp_mesh_background.glsl").bufferedReader().use { it.readText() }
        shader = RuntimeShader(source)
        shader.setFloatUniform("fade", 1f)
        paint = Paint().apply {
            this.shader = this@QpMeshPainter.shader
            isAntiAlias = false
        }
    }

    fun setResolution(width: Float, height: Float) {
        shader.setFloatUniform("resolution", max(1f, width), max(1f, height))
    }
    fun setTime(seconds: Float) = shader.setFloatUniform("time", seconds)
    fun setFade(value: Float) = shader.setFloatUniform("fade", value.coerceIn(0f, 1f))
    fun setCovers(current: QpPreparedCover, previous: QpPreparedCover) {
        shader.setInputShader("cover", BitmapShader(current.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("coverPrev", BitmapShader(previous.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }
    fun setLookups(current: Bitmap, previous: Bitmap) {
        shader.setInputShader("lookup", BitmapShader(current, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("lookupPrev", BitmapShader(previous, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }
}
