package com.yunyin.music.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.max

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
 * Lyric share card rendered with the platform Canvas (no Compose off-screen measurement, no dependency).
 *
 * Layout ideas are borrowed from **luzhenhua/LyricsCard** (MIT, Apple Music-style lyric cards) — blurred-cover
 * backdrop replaced by a cover-derived colour wash, a large title, a muted artist line, and the lyric block as the
 * typographic hero with generous leading. AMLL (`amll-dev/applemusic-like-lyrics`, AGPL-3.0) informed only the
 * cover-band + gradient idea; no AGPL code is used. Our own drawing code follows the app's typography and accent.
 *
 * Two sizes are supported: 1080×1440 (feed) and 1080×1920 (story); the latter only changes vertical rhythm.
 */
object LyricsShareCard {

    const val WIDTH = 1080
    const val FEED_HEIGHT = 1440
    const val STORY_HEIGHT = 1920

    fun render(input: LyricsShareInput, story: Boolean = false): Bitmap {
        val height = if (story) STORY_HEIGHT else FEED_HEIGHT
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val dominant = dominantColor(input.cover)
        drawBackdrop(canvas, input.cover, dominant, height)

        val left = 96f
        val right = WIDTH - 96f
        val titlePaint = textPaint(if (story) 84f else 76f, Color.WHITE, bold = true)
        val artistPaint = textPaint(34f, Color.argb(200, 255, 255, 255), bold = false)
        val lyricPaint = textPaint(if (story) 56f else 52f, Color.argb(238, 255, 255, 255), bold = false)
        val highlightPaint = textPaint(if (story) 58f else 54f, input.accent, bold = true)
        val markPaint = textPaint(24f, Color.argb(120, 255, 255, 255), bold = false)

        val coverSize = if (story) 220f else 200f
        input.cover?.let { cover ->
            val rect = RectF(left, height * 0.10f, left + coverSize, height * 0.10f + coverSize)
            val path = android.graphics.Path().apply { addRoundRect(rect, 28f, 28f, android.graphics.Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }

        // Title is the largest object on the card; artist is clearly subordinate.
        var y = height * 0.10f + coverSize + 110f
        drawFitted(canvas, input.songName, left, right, y, titlePaint)
        y += 58f
        canvas.drawText(input.artistLine.ifBlank { "未知艺人" }, left, y, artistPaint)

        // Lyric block: the hero. Four lines max, generous leading, current line in accent.
        val lines = if (input.lyricLines.isEmpty()) listOf("愿这首歌陪你此刻") else input.lyricLines.take(4)
        y = height * (if (story) 0.50f else 0.48f)
        val lineHeight = (if (story) 56f else 52f) * 1.55f
        lines.forEachIndexed { index, line ->
            val paint = if (index == input.highlightIndex.coerceIn(0, lines.lastIndex)) highlightPaint else lyricPaint
            canvas.drawText(line, left, y, paint)
            y += lineHeight
        }

        // Discreet app mark at the bottom right, with a thin accent rule.
        canvas.drawRect(left, height - 170f, left + 64f, height - 165f, Paint().apply { color = input.accent })
        val mark = "云音 · YunYin"
        markPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(mark, right, height - 110f, markPaint)
        markPaint.textAlign = Paint.Align.LEFT
        return bitmap
    }

    private fun drawBackdrop(canvas: Canvas, cover: Bitmap?, dominant: Int, height: Int) {
        val base = Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), darken(dominant, 0.35f), Color.rgb(8, 8, 11), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), base)
        if (cover == null) return
        // Cover band at the top; it dissolves into the colour wash through a vertical gradient.
        val scale = max(WIDTH.toFloat() / cover.width, (height * 0.45f) / cover.height)
        val w = cover.width * scale
        val h = cover.height * scale
        val src = Rect(0, 0, cover.width, cover.height)
        val dst = RectF((WIDTH - w) / 2f, 0f, (WIDTH + w) / 2f, h)
        canvas.drawBitmap(cover, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 })
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height * 0.62f,
                intArrayOf(Color.argb(20, 0, 0, 0), Color.argb(190, 8, 8, 11), Color.rgb(8, 8, 11)),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height * 0.62f, fade)
    }

    private fun dominantColor(cover: Bitmap?): Int {
        if (cover == null) return Color.rgb(46, 46, 52)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        val stepX = (cover.width / 10).coerceAtLeast(1)
        val stepY = (cover.height / 10).coerceAtLeast(1)
        var x = stepX / 2
        while (x < cover.width) {
            var y = stepY / 2
            while (y < cover.height) {
                val pixel = runCatching { cover.getPixel(x, y) }.getOrNull() ?: continue
                r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); count++
                y += stepY
            }
            x += stepX
        }
        if (count == 0) return Color.rgb(46, 46, 52)
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun drawFitted(canvas: Canvas, text: String, left: Float, right: Float, baseline: Float, paint: Paint) {
        var size = paint.textSize
        while (size > 40f && paint.measureText(text) > right - left) {
            size -= 2f
            paint.textSize = size
        }
        canvas.drawText(text, left, baseline, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /**
     * Writes the feed card (and optional story variant) into `Pictures/云音` and opens the share sheet.
     *
     * Scoped storage needs no runtime permission for the app's own MediaStore insert. Any failure is returned so the
     * caller can show the concrete reason; playback is never involved.
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
