package dev.photowidget.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import kotlin.math.sqrt

/** Pixel dimensions used to render one widget update. */
data class WidgetSize(val widthPx: Int, val heightPx: Int) {
    val isValid: Boolean get() = widthPx > 0 && heightPx > 0
    fun scaled(factor: Float): WidgetSize =
        WidgetSize(
            (widthPx * factor).toInt().coerceAtLeast(32),
            (heightPx * factor).toInt().coerceAtLeast(32),
        )
}

/**
 * Converts launcher cell sizes into bitmap sizes.
 *
 * RemoteViews bitmaps travel through Binder, so the pixel count is capped. If a
 * launcher rejects the update anyway, the coordinator retries with a smaller
 * bitmap instead of leaving the widget stale.
 */
object WidgetSizing {

    /** ~1.2 MB of ARGB pixels, safely below the Binder transaction limit. */
    const val MAX_PIXELS = 300_000

    const val DEFAULT_WIDTH_DP = 250
    const val DEFAULT_HEIGHT_DP = 180

    fun fromOptions(options: Bundle?, density: Float): WidgetSize {
        val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        val widthDp = when {
            minWidth > 0 -> minWidth
            maxWidth > 0 -> maxWidth
            else -> DEFAULT_WIDTH_DP
        }
        val heightDp = when {
            minHeight > 0 -> minHeight
            maxHeight > 0 -> maxHeight
            else -> DEFAULT_HEIGHT_DP
        }
        val safeDensity = density.takeIf { it > 0f } ?: 2f
        return clamp((widthDp * safeDensity).toInt(), (heightDp * safeDensity).toInt())
    }

    fun clamp(widthPx: Int, heightPx: Int, maxPixels: Int = MAX_PIXELS): WidgetSize {
        val width = widthPx.coerceIn(32, 2400)
        val height = heightPx.coerceIn(32, 2400)
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return WidgetSize(width, height)
        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        return WidgetSize(
            (width * scale).toInt().coerceAtLeast(32),
            (height * scale).toInt().coerceAtLeast(32),
        )
    }
}
