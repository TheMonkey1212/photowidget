package dev.photowidget.photos

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Small, dependency-free blur used for Blur Fill mode and blurred backgrounds.
 *
 * The image is downscaled first, blurred with a separable box filter and drawn
 * back scaled up by the renderer. That is visually indistinguishable from a
 * heavy Gaussian blur behind a widget-sized photo and costs a fraction of the
 * memory and CPU of blurring at full resolution.
 */
object BlurKit {

    /**
     * @param strength 0..1; 0 disables blurring (the downscale still smooths).
     * @param maxDimension longest side of the produced bitmap.
     */
    fun blurred(
        source: Bitmap,
        strength: Float = 0.75f,
        maxDimension: Int = 128,
    ): Bitmap {
        val longest = max(source.width, source.height)
        val scale = if (longest > maxDimension) maxDimension.toFloat() / longest else 1f
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== source) small.recycle()

        val radius = (min(width, height) * 0.32f * strength.coerceIn(0f, 1f))
            .toInt()
            .coerceIn(0, min(width, height) / 2)
        if (radius >= 1) {
            val passes = if (strength > 0.6f) 3 else 2
            repeat(passes) {
                boxBlurHorizontal(pixels, width, height, radius)
                boxBlurVertical(pixels, width, height, radius)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurHorizontal(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (x in -radius..radius) {
                val c = pixels[row + x.coerceIn(0, width - 1)]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
            for (x in 0 until width) {
                out[row + x] = pack(sumA / count, sumR / count, sumG / count, sumB / count)
                val remove = pixels[row + (x - radius).coerceIn(0, width - 1)]
                val add = pixels[row + (x + radius + 1).coerceIn(0, width - 1)]
                sumA += ((add ushr 24) and 0xFF) - ((remove ushr 24) and 0xFF)
                sumR += ((add ushr 16) and 0xFF) - ((remove ushr 16) and 0xFF)
                sumG += ((add ushr 8) and 0xFF) - ((remove ushr 8) and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
        out.copyInto(pixels)
    }

    private fun boxBlurVertical(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val out = IntArray(pixels.size)
        for (x in 0 until width) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (y in -radius..radius) {
                val c = pixels[y.coerceIn(0, height - 1) * width + x]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
            for (y in 0 until height) {
                out[y * width + x] = pack(sumA / count, sumR / count, sumG / count, sumB / count)
                val remove = pixels[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = pixels[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sumA += ((add ushr 24) and 0xFF) - ((remove ushr 24) and 0xFF)
                sumR += ((add ushr 16) and 0xFF) - ((remove ushr 16) and 0xFF)
                sumG += ((add ushr 8) and 0xFF) - ((remove ushr 8) and 0xFF)
                sumB += (add and 0xFF) - (remove and 0xFF)
            }
        }
        out.copyInto(pixels)
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)
}
