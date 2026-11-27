package dev.photowidget.photos

import android.graphics.Bitmap
import dev.photowidget.model.RenderQuality

/**
 * Abstract bitmap access so the renderer and widget coordinator can be tested
 * with deterministic in-memory images instead of real photo files.
 */
interface PhotoImageLoader {
    fun load(
        uri: String,
        targetWidth: Int,
        targetHeight: Int,
        quality: RenderQuality = RenderQuality.NORMAL,
    ): Bitmap?
}
