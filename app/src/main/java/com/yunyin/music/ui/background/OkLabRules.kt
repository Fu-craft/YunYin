package com.yunyin.music.ui.background

import kotlin.math.cbrt
import kotlin.math.pow

/** One colour in OkLab. Independent implementation from the public OkLab specification. */
data class OkLab(val l: Float, val a: Float, val b: Float)

/**
 * Clean-room OkLab helpers for the 色阶流动 renderer.
 *
 * Inspired by AMLL's use of OkLab for perceptually smooth palette transitions (AGPL-3.0); this
 * implementation is written from the published OkLab transform, with no code taken from AMLL.
 */
object OkLabRules {

    fun fromSrgb(rgb: Int): OkLab {
        fun linear(channel: Int): Float {
            val c = (channel and 0xFF) / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = linear(rgb ushr 16)
        val g = linear(rgb ushr 8)
        val b = linear(rgb)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return OkLab(
            l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    fun toSrgb(color: OkLab): Int {
        val l = (color.l + 0.3963377774f * color.a + 0.2158037573f * color.b)
        val m = (color.l - 0.1055613458f * color.a - 0.0638541728f * color.b)
        val s = (color.l - 0.0894841775f * color.a - 1.2914855480f * color.b)
        val ll = l * l * l
        val mm = m * m * m
        val ss = s * s * s
        val r = 4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss
        val g = -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss
        val b = -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss
        fun encode(c: Float): Int {
            val v = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f
            return (v * 255f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (encode(r) shl 16) or (encode(g) shl 8) or encode(b)
    }

    fun mix(from: OkLab, to: OkLab, progress: Float): OkLab {
        val t = progress.coerceIn(0f, 1f)
        return OkLab(
            l = from.l + (to.l - from.l) * t,
            a = from.a + (to.a - from.a) * t,
            b = from.b + (to.b - from.b) * t,
        )
    }

    fun smoothstep(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Blends two ARGB palettes through OkLab; used for the ~1 s track transition. */
    fun transitionPalette(from: IntArray, to: IntArray, progress: Float): IntArray {
        if (progress <= 0f) return from.copyOf()
        if (progress >= 1f) return to.copyOf()
        val t = smoothstep(progress)
        val count = minOf(from.size, to.size)
        return IntArray(count) { i -> toSrgb(mix(fromSrgb(from[i]), fromSrgb(to[i]), t)) }
    }

    fun toShaderArray(colors: IntArray): FloatArray =
        FloatArray(colors.size * 3) { i ->
            when (i % 3) {
                0 -> colors[i / 3] ushr 16 and 0xFF
                1 -> colors[i / 3] ushr 8 and 0xFF
                else -> colors[i / 3] and 0xFF
            }.let { it / 255f }
        }
}
