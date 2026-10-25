// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
// Portions ported from NeriPlayer (GPL-3.0), © NeriPlayer contributors.
// Source: app/src/main/java/moe/ouom/neriplayer/ui/component/lyrics/LyricShareSheet.kt

package com.yunyin.music.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import com.yunyin.music.data.share.NeriLyricCardRules

/** Everything the lyric share card needs, already resolved by the caller. */
data class LyricsShareInput(
    val songName: String,
    val artistLine: String,
    val lyricLines: List<String>,
    val highlightIndex: Int,
    val cover: Bitmap?,
    val accent: Int = Color.WHITE,
)

/**
 * NeriPlayer-style lyric share card.
 *
 * The design is the square NeriPlayer card (1080 px, 88 px padding, 76 px corner, 214 px glass
 * information bar, 66/44 px lyrics), extended to our 1080×1440 feed and 1080×1920 story canvases.
 * The lyric block is vertically centred in the larger area; the information hierarchy is unchanged:
 * brand at the top, lyrics as the hero, cover + title + artist in the bottom glass bar.
 *
 * The background is a CPU-preblurred cover (135 px source, three box-blur passes, upscaled) so it is
 * a texture rather than competing artwork. The same blur is reused for the bottom glass. Rendering is
 * entirely CPU and the caller already runs it off the main thread.
 */
object LyricsShareCard {

    const val WIDTH = NeriLyricCardRules.WIDTH
    const val FEED_HEIGHT = NeriLyricCardRules.FEED_HEIGHT
    const val STORY_HEIGHT = NeriLyricCardRules.STORY_HEIGHT

    fun render(input: LyricsShareInput, story: Boolean = false): Bitmap {
        val height = NeriLyricCardRules.height(story)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val widthF = WIDTH.toFloat()
        val heightF = height.toFloat()
        val cardRect = RectF(0f, 0f, widthF, heightF)
        val cardPath = Path().apply {
            addRoundRect(cardRect, NeriLyricCardRules.CORNER, NeriLyricCardRules.CORNER, Path.Direction.CW)
        }
        val blurred = input.cover?.let { runCatching { buildBlurredCover(it, WIDTH) }.getOrNull() }
        try {
            canvas.save()
            canvas.clipPath(cardPath)
            drawBackground(canvas, cardRect, input.cover, blurred)
            drawBrand(canvas)
            drawLyrics(canvas, input, height)
            drawInfoBar(canvas, input, blurred, height)
            canvas.restore()
        } finally {
            blurred?.recycle()
        }
        return bitmap
    }

    private fun drawBackground(
        canvas: Canvas,
        rect: RectF,
        cover: Bitmap?,
        blurred: Bitmap?,
    ) {
        if (blurred != null) {
            canvas.drawBitmap(
                blurred,
                null,
                rect,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            val seed = cover?.averageColor() ?: Color.rgb(156, 148, 80)
            val start = blend(seed, Color.WHITE, 0.18f)
            val end = blend(seed, Color.BLACK, 0.28f)
            canvas.drawRect(
                rect,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        intArrayOf(start, seed, end),
                        floatArrayOf(0f, 0.62f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
        }
        // NeriPlayer's exact scrim: white 86 → black 64 at 48% → black 118.
        canvas.drawRect(
            rect,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(
                        Color.argb(86, 255, 255, 255),
                        Color.argb(64, 0, 0, 0),
                        Color.argb(118, 0, 0, 0),
                    ),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun drawBrand(canvas: Canvas) {
        val padding = NeriLyricCardRules.PADDING
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(142, 255, 255, 255)
            style = Paint.Style.FILL
        }
        // The compact three-dot mark is NeriPlayer's fallback icon geometry, scaled 1:1.
        canvas.drawCircle(padding + 12f, 108f, 16f, iconPaint)
        canvas.drawCircle(padding + 38f, 92f, 9f, iconPaint)
        canvas.drawCircle(padding + 38f, 124f, 9f, iconPaint)
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(142, 255, 255, 255)
            textSize = NeriLyricCardRules.BRAND_TEXT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            "云音 · YunYin",
            padding + NeriLyricCardRules.BRAND_ICON + 18f,
            124f,
            brandPaint,
        )
    }

    private fun drawLyrics(canvas: Canvas, input: LyricsShareInput, height: Int) {
        val lines = input.lyricLines.take(4).filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val maxWidth = WIDTH - NeriLyricCardRules.PADDING * 2f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fitted = NeriLyricCardRules.fitSize(lines, maxWidth) { text, size ->
            textPaint.textSize = size
            textPaint.measureText(text)
        }
        textPaint.textSize = fitted
        val spacing = NeriLyricCardRules.lineSpacing(fitted)
        val lineHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        val blockHeight = lines.size * lineHeight + (lines.size - 1).coerceAtLeast(0) * spacing
        val maxHeight = NeriLyricCardRules.lyricsMaxHeight(height).toFloat()
        var y = NeriLyricCardRules.LYRICS_TOP +
            ((maxHeight - blockHeight) / 2f).coerceAtLeast(0f)
        lines.forEach { line ->
            val ellipsized = TextUtils.ellipsize(
                line,
                TextPaint(textPaint),
                maxWidth,
                TextUtils.TruncateAt.END,
            ).toString()
            val baseline = y - textPaint.fontMetrics.ascent
            canvas.drawText(ellipsized, NeriLyricCardRules.PADDING, baseline, textPaint)
            y += lineHeight + spacing
        }
    }

    private fun drawInfoBar(
        canvas: Canvas,
        input: LyricsShareInput,
        blurred: Bitmap?,
        height: Int,
    ) {
        val widthF = WIDTH.toFloat()
        val barHeight = NeriLyricCardRules.BOTTOM_BAR
        val top = height - barHeight
        val barRect = RectF(0f, top, widthF, height.toFloat())
        if (blurred != null) {
            canvas.save()
            canvas.clipRect(barRect)
            canvas.drawBitmap(
                blurred,
                null,
                RectF(0f, 0f, widthF, height.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 185 },
            )
            canvas.restore()
        }
        // NeriPlayer's white 70 glass, on top of the blurred repeat of the card.
        canvas.drawRect(
            barRect,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) },
        )
        val coverSize = NeriLyricCardRules.COVER_SIZE
        val coverTop = top + (barHeight - coverSize) / 2f
        val coverRect = RectF(
            NeriLyricCardRules.PADDING,
            coverTop,
            NeriLyricCardRules.PADDING + coverSize,
            coverTop + coverSize,
        )
        drawCover(canvas, input.cover, coverRect, input.songName)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = NeriLyricCardRules.TITLE_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(224, 255, 255, 255)
            textSize = NeriLyricCardRules.ARTIST_SIZE
            typeface = Typeface.DEFAULT
        }
        val textLeft = NeriLyricCardRules.PADDING + coverSize + 28f
        val textWidth = widthF - textLeft - NeriLyricCardRules.PADDING
        drawSingleLine(canvas, input.songName, textLeft, top + 82f, textWidth, titlePaint)
        drawSingleLine(
            canvas,
            input.artistLine.ifBlank { "未知艺人" },
            textLeft,
            top + 136f,
            textWidth,
            artistPaint,
        )
    }

    private fun drawCover(canvas: Canvas, cover: Bitmap?, rect: RectF, fallbackText: String) {
        if (cover != null) {
            val path = Path().apply {
                addRoundRect(rect, NeriLyricCardRules.COVER_CORNER, NeriLyricCardRules.COVER_CORNER, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(cover, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                Color.rgb(229, 88, 112), Color.rgb(90, 138, 168),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, NeriLyricCardRules.COVER_CORNER, NeriLyricCardRules.COVER_CORNER, paint)
        val letter = fallbackText.trim().firstOrNull()?.toString().orEmpty()
        if (letter.isNotBlank()) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 58f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(letter, rect.centerX(), baseline, textPaint)
        }
    }

    private fun drawSingleLine(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        paint: TextPaint,
    ) {
        val ellipsized = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellipsized, x, baseline, paint)
    }

    /** CPU pre-blur: 135 px source, three radius-7 box passes, then upscale. */
    private fun buildBlurredCover(cover: Bitmap, targetSize: Int): Bitmap {
        val smallSize = (targetSize / 8).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(cover, smallSize, smallSize, true)
        return try {
            repeat(3) { boxBlurOnce(small, 7) }
            Bitmap.createScaledBitmap(small, targetSize, targetSize, true)
        } finally {
            small.recycle()
        }
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

    private fun Bitmap.averageColor(): Int {
        val stepX = (width / 48).coerceAtLeast(1)
        val stepY = (height / 48).coerceAtLeast(1)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = runCatching { getPixel(x, y) }.getOrNull()
                if (pixel != null) {
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return Color.rgb(156, 148, 80)
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun blend(color: Int, other: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) + (Color.red(other) - Color.red(color)) * f).toInt().coerceIn(0, 255),
            (Color.green(color) + (Color.green(other) - Color.green(color)) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) + (Color.blue(other) - Color.blue(color)) * f).toInt().coerceIn(0, 255),
        )
    }

    /**
     * Writes the feed card (and optional story variant) into `Pictures/云音` and opens the share sheet.
     * The app's own MediaStore insert needs no runtime permission; any failure is returned so the
     * caller can show the reason. Rendering happens before this call, off the main thread.
     */
    fun saveAndShare(context: Context, bitmap: Bitmap, story: Bitmap? = null): Result<Unit> = runCatching {
        val uris = ArrayList<Uri>()
        uris += writeImage(context, bitmap, "feed")
        story?.let { uris += writeImage(context, it, "story") }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享歌词卡片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeImage(context: Context, bitmap: Bitmap, suffix: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "yunyin-lyrics-$suffix-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/云音")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("图片编码失败")
            } ?: error("无法写入图片文件")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }
}
