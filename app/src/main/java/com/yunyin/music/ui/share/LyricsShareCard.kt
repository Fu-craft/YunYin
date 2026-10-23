package com.yunyin.music.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import com.yunyin.music.data.share.LyricsCardLayoutRules
import com.yunyin.music.data.share.LyricsTitleFitRules

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
 * Lyric share card. Layout ideas from luzhenhua/LyricsCard (MIT): rounded cover thumb + title/artist header,
 * cover-derived colour field, lyric block as the hero. AMLL (AGPL-3.0) informed only the blurred-cover idea;
 * all drawing code here is ours.
 *
 * The cover is downscaled to a 40 px texture and blown back up, so no title/artwork detail survives: it reads as a
 * soft background texture instead of competing with our typography. Below it a solid gradient built from the cover's
 * dominant colour keeps contrast stable. The lyric block is vertically centred in the remaining space, one type
 * scale, 1.55 leading, current line accent + bold, other lines at ~78 % white.
 *
 * Two outputs share the same design system: 1080x1440 feed and 1080x1920 story.
 */
object LyricsShareCard {

    const val WIDTH = 1080
    const val FEED_HEIGHT = 1440
    const val STORY_HEIGHT = 1920

    fun render(input: LyricsShareInput, story: Boolean = false): Bitmap {
        val height = if (story) STORY_HEIGHT else FEED_HEIGHT
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val margin = LyricsCardLayoutRules.marginPx(WIDTH).toFloat()
        val dominant = dominantColor(input.cover)
        drawBackdrop(canvas, input.cover, dominant, height)

        val titleSize = if (story) 84f else 76f
        val titlePaint = textPaint(titleSize, Color.WHITE, bold = true)
        val artistPaint = textPaint(34f, Color.argb(205, 255, 255, 255), bold = false)
        val lyricSize = if (story) 54f else 50f
        val lyricPaint = textPaint(lyricSize, Color.argb(200, 255, 255, 255), bold = false)
        val currentPaint = textPaint(lyricSize, Color.WHITE, bold = true)
        val markPaint = textPaint(24f, Color.argb(135, 255, 255, 255), bold = false)

        // Header: small rounded cover thumb + typographic hierarchy.
        val thumb = if (story) 252f else 232f
        input.cover?.let { cover ->
            val rect = RectF(margin, margin, margin + thumb, margin + thumb)
            val path = android.graphics.Path().apply { addRoundRect(rect, 30f, 30f, android.graphics.Path.Direction.CW) }
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            canvas.drawRoundRect(rect, 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(70, 255, 255, 255)
            })
        }
        val headerTop = margin + thumb + 60f
        // Single-line title: auto-shrink from the design size, then ellipsise at the floor. The title block
        // height is a constant (base size * 1.2), so lyric-block centring never shifts with title length.
        val titleWidth = (WIDTH - margin) - margin
        val fittedSize = LyricsTitleFitRules.fitSize(
            text = input.songName,
            maxWidth = titleWidth,
            baseSize = titleSize,
            measure = { value, size -> titlePaint.textSize = size; titlePaint.measureText(value) },
        )
        titlePaint.textSize = fittedSize
        val titleText = if (LyricsTitleFitRules.needsEllipsis(
                input.songName, titleWidth, titleSize.coerceAtMost(LyricsTitleFitRules.MIN_SIZE),
                measure = { value, size -> titlePaint.textSize = size; titlePaint.measureText(value) },
            )
        ) {
            TextUtils.ellipsize(input.songName, TextPaint(titlePaint), titleWidth, TextUtils.TruncateAt.END).toString()
        } else {
            input.songName
        }
        val titleBlockHeight = titleSize * 1.2f
        canvas.drawText(titleText, margin, headerTop + titleSize * 0.95f, titlePaint)
        val artistTop = headerTop + titleBlockHeight + 34f
        val artistText = TextUtils.ellipsize(
            input.artistLine.ifBlank { "未知艺人" }, TextPaint(artistPaint), titleWidth, TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(artistText, margin, artistTop, artistPaint)
        val topAreaBottom = artistTop + 80f

        // Lyric hero: centred in the space above the bottom mark, one type scale.
        val lines = if (input.lyricLines.isEmpty()) listOf("愿这首歌陪你此刻") else input.lyricLines.take(4)
        val blockHeight = LyricsCardLayoutRules.lyricBlockHeight(lines.size, lyricSize)
        val bottomReserve = height * 0.12f
        var y = LyricsCardLayoutRules.lyricBlockTop(height.toFloat().toInt(), topAreaBottom, bottomReserve, blockHeight)
        val highlight = input.highlightIndex.coerceIn(0, lines.lastIndex)
        val leading = LyricsCardLayoutRules.lyricLineHeight(lyricSize)
        lines.forEachIndexed { index, line ->
            val paint = if (index == highlight) currentPaint else lyricPaint
            if (index == highlight) {
                // Thin accent bar marks the current line; the text itself stays the same size and colour family.
                canvas.drawRect(margin - 18f, y + 2f, margin - 14f, y + lyricSize + 2f,
                    Paint().apply { color = input.accent })
            }
            canvas.drawText(line, margin, y + lyricSize, paint)
            y += leading
        }

        // Bottom mark + thin accent rule, both inside the safe margin.
        val ruleY = height - bottomReserve
        canvas.drawRect(margin, ruleY, margin + 64f, ruleY + 3f, Paint().apply { color = input.accent })
        markPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("云音 · YunYin", WIDTH - margin, ruleY + 58f, markPaint)
        markPaint.textAlign = Paint.Align.LEFT
        return bitmap
    }

    private fun drawBackdrop(canvas: Canvas, cover: Bitmap?, dominant: Int, height: Int) {
        canvas.drawColor(Color.rgb(8, 8, 11))
        if (cover != null) {
            // 40 px wide source destroyed the artwork's legibility; drawn back up it is a texture.
            val tiny = runCatching {
                val scale = 40f / cover.width.coerceAtLeast(1)
                Bitmap.createScaledBitmap(cover, 40, (cover.height * scale).toInt().coerceAtLeast(40), true)
            }.getOrNull()
            if (tiny != null) {
                canvas.drawBitmap(tiny, null, RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 })
            }
        }
        val wash = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(withAlpha(darken(dominant, 0.55f), 225), withAlpha(darken(dominant, 0.30f), 235), Color.rgb(8, 8, 11)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), wash)
        // Vignette prevents a flat field at the edges; deterministic grain hides gradient banding.
        val vignette = Paint().apply {
            shader = android.graphics.RadialGradient(
                WIDTH / 2f, height / 2f, height * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), vignette)
        val grain = Paint().apply { color = Color.argb(10, 255, 255, 255); strokeWidth = 1.5f }
        var seed = 17
        repeat(700) {
            seed = seed * 1103515245 + 12345
            val x = ((seed ushr 8) and 0x7FFFFFFF) % WIDTH
            val y = ((seed ushr 4) and 0x7FFFFFFF) % height
            canvas.drawPoint(x.toFloat(), y.toFloat(), grain)
        }
    }

    private fun dominantColor(cover: Bitmap?): Int {
        if (cover == null) return Color.rgb(44, 44, 52)
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        val stepX = (cover.width / 12).coerceAtLeast(1)
        val stepY = (cover.height / 12).coerceAtLeast(1)
        var x = stepX / 2
        while (x < cover.width) {
            var y = stepY / 2
            while (y < cover.height) {
                val pixel = runCatching { cover.getPixel(x, y) }.getOrNull()
                if (pixel != null) {
                    r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); count++
                }
                y += stepY
            }
            x += stepX
        }
        if (count == 0) return Color.rgb(44, 44, 52)
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

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
     * Writes the feed card (and optional story variant) into `Pictures/云音` and opens the share sheet. The app's own
     * MediaStore insert needs no runtime permission; any failure is returned so the caller can show the reason.
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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片文件")
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
