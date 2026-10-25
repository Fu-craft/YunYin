// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

/** Per-cover average colour and soft-edge bitmap caches; both are computed off the main thread. */
internal object CoverFieldCache {

    private val averages = LruCache<String, Int>(24)
    private val blurs = LruCache<String, ImageBitmap>(8)

    fun average(trackId: Long, cover: ImageBitmap): Int? {
        val key = "$trackId:${cover.hashCode()}"
        averages.get(key)?.let { return it }
        return runCatching {
            val source = cover.asAndroidBitmap()
            val small = Bitmap.createScaledBitmap(source, 16, 16, true)
            val pixels = IntArray(16 * 16)
            small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            small.recycle()
            FullScreenCoverRules.averageColor(pixels)?.also { averages.put(key, it) }
        }.getOrNull()
    }

    /**
     * Small blurred copy, cached per cover/radius. Downscale 4×, blur the small bitmap, then let
     * the Image upscale it: a pre-rendered bitmap, not a live RenderEffect.
     */
    fun blurred(trackId: Long, cover: ImageBitmap, radiusPx: Float): ImageBitmap? {
        val key = "$trackId:${cover.hashCode()}:${radiusPx.roundToInt()}"
        blurs.get(key)?.let { return it }
        return runCatching {
            val source = cover.asAndroidBitmap()
            val scale = FullScreenCoverRules.BLUR_DOWNSCALE
            val w = (source.width / scale).coerceAtLeast(1)
            val h = (source.height / scale).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(source, w, h, true)
            val radius = (radiusPx / scale).roundToInt().coerceAtLeast(1)
            repeat(2) { boxBlurOnce(small, radius) }
            small.asImageBitmap().also { blurs.put(key, it) }
        }.getOrNull()
    }

    private fun boxBlurOnce(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val p = pixels[y * w + (x + k).coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xFF; r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF; b += p and 0xFF
                }
                val d = 2 * radius + 1
                temp[y * w + x] = (a / d shl 24) or (r / d shl 16) or (g / d shl 8) or (b / d)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val p = temp[(y + k).coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xFF; r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF; b += p and 0xFF
                }
                val d = 2 * radius + 1
                pixels[y * w + x] = (a / d shl 24) or (r / d shl 16) or (g / d shl 8) or (b / d)
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
