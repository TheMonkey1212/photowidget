package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import dev.photowidget.model.BackgroundStyle
import dev.photowidget.model.ImageFit
import dev.photowidget.model.OverlayAppearance
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetConfig
import dev.photowidget.photos.BlurKit
import dev.photowidget.photos.PhotoImageLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a complete widget frame into a bitmap.
 *
 * Rendering everything (photo, corner radius, padding, border, shadow, overlays)
 * in one bitmap keeps the launcher layout trivial and makes the in-app preview
 * pixel-identical to the widget. Photos are always scaled proportionally; Fill
 * crops to cover and never stretches the image.
 */
class WidgetRenderer(
    private val context: Context,
    private val images: PhotoImageLoader,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() },
) {

    private val density = context.resources.displayMetrics.density

    fun render(
        config: WidgetConfig,
        photos: List<PhotoRef>,
        widthPx: Int,
        heightPx: Int,
        now: Long = System.currentTimeMillis(),
    ): Bitmap {
        val width = widthPx.coerceAtLeast(32)
        val height = heightPx.coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawFrame(Canvas(bitmap), width.toFloat(), height.toFloat(), config, photos, now)
        return bitmap
    }

    /** Empty / error state with the same shape as a normal widget. */
    fun renderPlaceholder(
        appearance: WidgetAppearance,
        widthPx: Int,
        heightPx: Int,
        title: String,
        subtitle: String?,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(32)
        val height = heightPx.coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val source = appearance.sanitized()
        // The placeholder must stay readable, so it always gets a solid card even
        // when the user turned the backing card off.
        val a = source.copy(
            showCard = true,
            backgroundStyle = if (source.backgroundStyle == BackgroundStyle.TRANSPARENT) {
                BackgroundStyle.SOLID
            } else {
                source.backgroundStyle
            },
            backgroundColor = if (source.backgroundStyle == BackgroundStyle.TRANSPARENT) {
                0xFF161616
            } else {
                source.backgroundColor
            },
        )
        val card = drawCard(canvas, width.toFloat(), height.toFloat(), a, null, emptyList())

        val titleSize = sp(min(18f, max(11f, card.height() / density / 9f)))
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = titleSize
        }
        val maxWidth = (card.width() - 24f * density).coerceAtLeast(24f)
        fitToWidth(text, title, maxWidth, sp(9f))
        val cx = card.centerX()
        val hasSubtitle = !subtitle.isNullOrBlank()
        var baseline = if (hasSubtitle) {
            card.centerY() - text.descent()
        } else {
            card.centerY() + (text.descent() - text.ascent()) / 2f - text.descent()
        }
        canvas.drawText(title, cx, baseline, text)

        if (hasSubtitle) {
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text.color = Color.argb(200, 255, 255, 255)
            text.textSize = (titleSize * 0.72f).coerceAtLeast(sp(8f))
            fitToWidth(text, subtitle!!, maxWidth, sp(8f))
            baseline += text.textSize * 1.7f
            canvas.drawText(subtitle, cx, baseline, text)
        }
        return bitmap
    }

    private fun drawFrame(
        canvas: Canvas,
        width: Float,
        height: Float,
        config: WidgetConfig,
        photos: List<PhotoRef>,
        now: Long,
    ) {
        val a = config.appearance.sanitized()
        val card = drawCard(canvas, width, height, a, null, photos)
        val radius = cardRadius(a, card)

        val padding = (a.paddingDp * density).coerceAtMost(min(card.width(), card.height()) * 0.35f)
        val inner = RectF(card.left + padding, card.top + padding, card.right - padding, card.bottom - padding)
        if (inner.width() <= 4f || inner.height() <= 4f) return

        canvas.save()
        canvas.clipPath(roundPath(card, radius))
        if (photos.size > 1) {
            drawCollage(canvas, inner, a, photos)
        } else {
            drawSinglePhoto(canvas, inner, a, photos.firstOrNull())
        }
        if (a.overlay.enabled) {
            drawOverlay(canvas, inner, a.overlay, photos.firstOrNull(), now)
        }
        canvas.restore()

        if (a.showCard && a.borderWidthDp > 0f) {
            val stroke = a.borderWidthDp * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = a.borderColor.toInt()
            }
            val inset = stroke / 2f
            canvas.drawRoundRect(
                RectF(card.left + inset, card.top + inset, card.right - inset, card.bottom - inset),
                max(0f, radius - inset),
                max(0f, radius - inset),
                paint,
            )
        }
    }

    /** Draws shadow + background, returns the card rectangle. */
    private fun drawCard(
        canvas: Canvas,
        width: Float,
        height: Float,
        a: WidgetAppearance,
        backgroundOverride: Int?,
        photos: List<PhotoRef>,
    ): RectF {
        // Without a card there is nothing behind the photo: no margin, shadow, border
        // or background, so the widget is just the image (and stays transparent
        // wherever the photo does not reach).
        if (!a.showCard) return RectF(0f, 0f, width, height)
        val margin = (a.marginDp * density).coerceIn(0f, min(width, height) * 0.3f)
        val shadowRadius = if (a.shadowEnabled) {
            (a.shadowSizeDp * density).coerceAtMost(min(width, height) * 0.08f)
        } else {
            0f
        }
        val outer = if (a.shadowEnabled) max(margin, shadowRadius + 1f) else margin
        val card = RectF(outer, outer, width - outer, height - outer)
        if (card.width() < 8f || card.height() < 8f) return RectF(0f, 0f, width, height)
        val radius = cardRadius(a, card)

        if (a.shadowEnabled && a.shadowStrength > 0f) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(1, 0, 0, 0)
                setShadowLayer(
                    shadowRadius,
                    0f,
                    shadowRadius * 0.35f,
                    Color.argb((255 * a.shadowStrength).toInt().coerceIn(1, 255), 0, 0, 0),
                )
            }
            canvas.drawRoundRect(card, radius, radius, shadowPaint)
        }

        canvas.save()
        canvas.clipPath(roundPath(card, radius))
        val fallbackColor = backgroundOverride ?: a.backgroundColor.toInt()
        when (a.backgroundStyle) {
            BackgroundStyle.SOLID -> canvas.drawRect(card, Paint().apply { color = fallbackColor })
            BackgroundStyle.TRANSPARENT -> Unit
            BackgroundStyle.BLUR -> {
                val photo = photos.firstOrNull()
                val source = photo?.let {
                    images.load(it.uri, card.width().toInt(), card.height().toInt(), a.renderQuality)
                }
                if (source != null) {
                    val blurred = BlurKit.blurred(source, a.backgroundBlur)
                    drawCover(canvas, blurred, card, 0.5f, 0.5f)
                    blurred.recycle()
                    if (a.backgroundDim > 0f) {
                        canvas.drawRect(
                            card,
                            Paint().apply { color = Color.argb((255 * a.backgroundDim).toInt(), 0, 0, 0) },
                        )
                    }
                } else {
                    canvas.drawRect(card, Paint().apply { color = fallbackColor })
                }
            }
        }
        canvas.restore()
        return card
    }

    private fun drawSinglePhoto(canvas: Canvas, inner: RectF, a: WidgetAppearance, photo: PhotoRef?) {
        val radius = min(cardRadius(a, inner), min(inner.width(), inner.height()) / 2f)
        if (photo == null) {
            drawMissing(canvas, inner, radius, a)
            return
        }
        val bitmap = images.load(photo.uri, inner.width().toInt(), inner.height().toInt(), a.renderQuality)
        if (bitmap == null) {
            drawMissing(canvas, inner, radius, a)
            return
        }
        when (a.imageFit) {
            ImageFit.FILL -> {
                canvas.save()
                canvas.clipPath(roundPath(inner, radius))
                drawCover(canvas, bitmap, inner, a.focusX, a.focusY)
                canvas.restore()
            }

            ImageFit.FIT -> {
                // Whole photo, its own aspect ratio, nothing painted beside it: the
                // leftover space stays transparent so the wallpaper shows through.
                val scale = min(inner.width() / bitmap.width, inner.height() / bitmap.height)
                if (!scale.isFinite() || scale <= 0f) return
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val content = RectF(
                    inner.centerX() - drawWidth / 2f,
                    inner.centerY() - drawHeight / 2f,
                    inner.centerX() + drawWidth / 2f,
                    inner.centerY() + drawHeight / 2f,
                )
                val contentRadius = min(cardRadius(a, content), min(drawWidth, drawHeight) / 2f)
                canvas.save()
                canvas.clipPath(roundPath(content, contentRadius))
                canvas.drawBitmap(
                    bitmap,
                    null,
                    content,
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
                canvas.restore()
            }

            ImageFit.BLUR_FILL -> {
                canvas.save()
                canvas.clipPath(roundPath(inner, radius))
                val blurred = BlurKit.blurred(bitmap, 0.95f)
                drawCover(canvas, blurred, inner, 0.5f, 0.5f)
                blurred.recycle()
                canvas.drawRect(inner, Paint().apply { color = Color.argb(48, 0, 0, 0) })
                drawContain(canvas, bitmap, inner)
                canvas.restore()
            }
        }
    }

    private fun drawCollage(canvas: Canvas, inner: RectF, a: WidgetAppearance, photos: List<PhotoRef>) {
        val (columns, rows) = collageGrid(photos.size, inner.width(), inner.height())
        val count = min(photos.size, columns * rows)
        val gap = (a.collageGapDp * density)
            .coerceAtMost(min(inner.width(), inner.height()) / (max(columns, rows) * 4f))
        val cellWidth = (inner.width() - gap * (columns - 1)) / columns
        val cellHeight = (inner.height() - gap * (rows - 1)) / rows
        if (cellWidth <= 2f || cellHeight <= 2f) return
        val cellRadius = min(cardRadius(a, inner) * 0.6f, min(cellWidth, cellHeight) / 6f)
        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        for (index in 0 until count) {
            val column = index % columns
            val row = index / columns
            val left = inner.left + column * (cellWidth + gap)
            val top = inner.top + row * (cellHeight + gap)
            val cell = RectF(left, top, left + cellWidth, top + cellHeight)
            val bitmap = images.load(photos[index].uri, cellWidth.toInt(), cellHeight.toInt(), a.renderQuality)
            if (bitmap == null) {
                drawMissing(canvas, cell, cellRadius, a, label = null)
                continue
            }
            canvas.save()
            canvas.clipPath(roundPath(cell, cellRadius))
            drawCover(canvas, bitmap, cell, a.focusX, a.focusY, filterPaint)
            canvas.restore()
        }
    }

    private fun drawMissing(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        a: WidgetAppearance,
        label: String? = "Unavailable",
    ) {
        canvas.drawRoundRect(
            rect,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // Over a card the plate only darkens it; without a card it must be
                // solid, or an unavailable photo would leave the widget invisible.
                color = if (a.showCard) Color.argb(90, 0, 0, 0) else Color.rgb(22, 22, 22)
            },
        )
        if (label == null) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = sp(10f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        if (rect.width() > text.measureText(label) * 1.2f && rect.height() > text.textSize * 2) {
            canvas.drawText(
                label,
                rect.centerX(),
                rect.centerY() + (text.descent() - text.ascent()) / 2f - text.descent(),
                text,
            )
        }
    }

    private fun drawOverlay(
        canvas: Canvas,
        inner: RectF,
        overlay: OverlayAppearance,
        photo: PhotoRef?,
        now: Long,
    ) {
        val o = overlay.sanitized()
        val lines = ArrayList<String>(4)
        if (o.customText.isNotBlank()) lines += o.customText
        if (o.showDate) lines += format(o.datePattern, now)
        if (o.showTime) lines += format(o.timePattern, now)
        if (o.showPhotoName && photo != null && photo.displayName.isNotBlank()) lines += photo.displayName
        if (o.showPhotoDate && photo != null && photo.dateTaken > 0L) lines += format(o.datePattern, photo.dateTaken)
        if (lines.isEmpty()) return

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = o.textColor.toInt()
            textSize = sp(o.textSizeSp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val inset = 10f * density
        val pad = 6f * density
        val availableWidth = (inner.width() - inset * 2 - pad * 2).coerceAtLeast(16f * density)
        var widest = lines.maxOf { text.measureText(it) }
        while (widest > availableWidth && text.textSize > sp(8f)) {
            text.textSize *= 0.92f
            widest = lines.maxOf { text.measureText(it) }
        }
        val lineHeight = (text.descent() - text.ascent()) * 1.12f
        val blockHeight = lineHeight * lines.size + pad * 2
        val blockWidth = widest + pad * 2

        val left = when {
            o.position.isStart -> inner.left + inset
            o.position.isEnd -> inner.right - inset - blockWidth
            else -> inner.centerX() - blockWidth / 2f
        }.coerceIn(inner.left + inset / 2f, max(inner.left + inset / 2f, inner.right - inset / 2f - blockWidth))
        val top = if (o.position.isTop) inner.top + inset else inner.bottom - inset - blockHeight

        if (o.scrim) {
            canvas.drawRoundRect(
                left,
                top,
                left + blockWidth,
                top + blockHeight,
                8f * density,
                8f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((255 * o.scrimStrength).toInt().coerceIn(0, 255), 0, 0, 0)
                },
            )
        }
        text.textAlign = when {
            o.position.isStart -> Paint.Align.LEFT
            o.position.isEnd -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        lines.forEachIndexed { index, line ->
            val x = when {
                o.position.isStart -> left + pad
                o.position.isEnd -> left + blockWidth - pad
                else -> left + blockWidth / 2f
            }
            val baseline = top + pad + lineHeight * index - text.ascent()
            canvas.drawText(line, x, baseline, text)
        }
    }

    private fun drawCover(
        canvas: Canvas,
        bitmap: Bitmap,
        dst: RectF,
        focusX: Float,
        focusY: Float,
        paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || dst.width() <= 0f || dst.height() <= 0f) return
        val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        if (!scale.isFinite() || scale <= 0f) return
        val srcWidth = (dst.width() / scale).coerceAtMost(bitmap.width.toFloat())
        val srcHeight = (dst.height() / scale).coerceAtMost(bitmap.height.toFloat())
        val maxLeft = (bitmap.width - srcWidth).coerceAtLeast(0f)
        val maxTop = (bitmap.height - srcHeight).coerceAtLeast(0f)
        val left = (maxLeft * focusX.coerceIn(0f, 1f)).coerceIn(0f, maxLeft)
        val top = (maxTop * focusY.coerceIn(0f, 1f)).coerceIn(0f, maxTop)
        val right = (left + srcWidth).coerceAtMost(bitmap.width.toFloat())
        val bottom = (top + srcHeight).coerceAtMost(bitmap.height.toFloat())
        val src = Rect(
            left.toInt().coerceIn(0, bitmap.width - 1),
            top.toInt().coerceIn(0, bitmap.height - 1),
            right.toInt().coerceIn(1, bitmap.width),
            bottom.toInt().coerceIn(1, bitmap.height),
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    private fun drawContain(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = min(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = dst.centerX() - drawWidth / 2f
        val top = dst.centerY() - drawHeight / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

    private fun cardRadius(a: WidgetAppearance, rect: RectF): Float =
        (a.cornerRadiusDp * density).coerceIn(0f, min(rect.width(), rect.height()) / 2f)

    private fun roundPath(rect: RectF, radius: Float): Path = Path().apply {
        addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    private fun fitToWidth(paint: TextPaint, text: String, maxWidth: Float, minSize: Float) {
        var guard = 0
        while (paint.measureText(text) > maxWidth && paint.textSize > minSize && guard < 40) {
            paint.textSize *= 0.92f
            guard++
        }
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    private fun format(pattern: String, time: Long): String = try {
        SimpleDateFormat(pattern, Locale.getDefault())
            .apply { timeZone = timeZoneProvider() }
            .format(Date(time))
    } catch (e: IllegalArgumentException) {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }

    companion object {
        /** Grid used by Collage mode; adapts to the widget's proportions. */
        fun collageGrid(count: Int, width: Float, height: Float): Pair<Int, Int> = when {
            count <= 1 -> 1 to 1
            count == 2 -> if (width >= height) 2 to 1 else 1 to 2
            count == 3 -> if (width >= height * 1.1f) 3 to 1 else 1 to 3
            count == 4 -> 2 to 2
            count <= 6 -> 3 to 2
            else -> 3 to 3
        }
    }
}
