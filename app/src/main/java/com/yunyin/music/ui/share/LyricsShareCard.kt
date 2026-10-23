package com.yunyin.music.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min

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
 * Draws a 1080x1440 portrait lyric card and exports it through MediaStore.
 *
 * The card is intentionally drawn with the platform Canvas: no Compose view needs to be measured off-screen and no
 * new dependency is involved. Layout: cover as a full-bleed tinted background (dark scrim keeps white type legible),
 * a soft accent bar, song / artist, up to four lyric lines with the current line in the accent colour, and the app
 * mark. When there are no lyrics the card degrades to cover + song + artist instead of failing.
 */
object LyricsShareCard {

    const val WIDTH = 1080
    const val HEIGHT = 1440

    fun render(input: LyricsShareInput): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(9, 9, 12))

        drawCover(canvas, input.cover)

        // Dark scrim so any cover (including a bright one) still holds white type at 4.5:1.
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })

        val left = 96f
        val right = WIDTH - 96f
        val titlePaint = textPaint(64f, Color.WHITE, bold = true)
        val artistPaint = textPaint(34f, Color.argb(190, 255, 255, 255), bold = false)
        val lyricPaint = textPaint(42f, Color.argb(230, 255, 255, 255), bold = false)
        val highlightPaint = textPaint(46f, input.accent, bold = true)
        val markPaint = textPaint(26f, Color.argb(150, 255, 255, 255), bold = false)

        // Cover thumbnail, rounded, top-left of the text block so the artwork stays recognisable even with the scrim.
        input.cover?.let { cover ->
            val size = 180f
            val rect = RectF(left, 120f, left + size, 120f + size)
            val path = android.graphics.Path().apply {
                addRoundRect(rect, 28f, 28f, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }

        var y = 390f
        canvas.drawText(input.songName, left, y, titlePaint)
        y += 52f
        canvas.drawText(input.artistLine.ifBlank { "未知艺人" }, left, y, artistPaint)
        y += 96f

        val lines = if (input.lyricLines.isEmpty()) {
            listOf("愿这首歌陪你此刻", "云音 · YunYin")
        } else {
            input.lyricLines.take(4)
        }
        val highlight = input.highlightIndex.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        lines.forEachIndexed { index, line ->
            val paint = if (index == highlight && input.lyricLines.isNotEmpty()) highlightPaint else lyricPaint
            drawWrapped(canvas, line, left, right, y, paint)
            y += if (index == highlight) 82f else 70f
        }

        // Accent bar + mark at the bottom.
        canvas.drawRect(left, HEIGHT - 150f, left + 72f, HEIGHT - 144f, Paint().apply { color = input.accent })
        canvas.drawText("云音 · YunYin", left, HEIGHT - 96f, markPaint)
        return bitmap
    }

    private fun drawCover(canvas: Canvas, cover: Bitmap?) {
        if (cover == null) return
        val scale = max(WIDTH.toFloat() / cover.width, HEIGHT.toFloat() / cover.height)
        val w = cover.width * scale
        val h = cover.height * scale
        val src = Rect(0, 0, cover.width, cover.height)
        val dst = RectF((WIDTH - w) / 2f, (HEIGHT - h) / 2f, (WIDTH + w) / 2f, (HEIGHT + h) / 2f)
        canvas.drawBitmap(cover, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 220 })
    }

    private fun drawWrapped(canvas: Canvas, text: String, left: Float, right: Float, baseline: Float, paint: Paint) {
        var line = ""
        var y = baseline
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > right - left && line.isNotEmpty()) {
                canvas.drawText(line, left, y, paint)
                y += paint.textSize * 1.25f
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line, left, y, paint)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /**
     * Writes [bitmap] into shared `Pictures/云音` and opens the system share sheet with the resulting content URI.
     *
     * Scoped storage means no runtime permission is needed for the app's own MediaStore insert. Any failure is
     * returned to the caller so it can show a concrete reason; playback is never involved.
     */
    fun saveAndShare(context: Context, bitmap: Bitmap): Result<Unit> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "yunyin-lyrics-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/云音")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
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
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享歌词卡片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
