package dev.photowidget.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import dev.photowidget.model.RenderQuality
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes photos at (roughly) the size the widget needs, applies EXIF rotation
 * and caches the result in a byte-bounded LRU cache. Never decodes full-size
 * images for a widget frame, which keeps memory flat on large libraries.
 */
class PhotoBitmapLoader(
    context: Context,
    cacheBytes: Int = defaultCacheBytes(),
    /** Opener for one photo; overridable in tests so decoding can be exercised directly. */
    private val openStream: (Uri) -> java.io.InputStream? = { uri ->
        context.applicationContext.contentResolver.openInputStream(uri)
    },
) : PhotoImageLoader {

    private val appContext = context.applicationContext
    private val cache = object : LruCache<String, Bitmap>(cacheBytes.coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    override fun load(
        uri: String,
        targetWidth: Int,
        targetHeight: Int,
        quality: RenderQuality,
    ): Bitmap? {
        if (uri.isBlank() || targetWidth <= 0 || targetHeight <= 0) return null
        val factor = when (quality) {
            RenderQuality.HIGH -> 1.35f
            RenderQuality.NORMAL -> 1.0f
            RenderQuality.LOW -> 0.7f
        }
        val width = (targetWidth * factor).toInt().coerceIn(1, MAX_DIMENSION)
        val height = (targetHeight * factor).toInt().coerceIn(1, MAX_DIMENSION)
        val key = "$uri|${width}x$height"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val decoded = decode(uri, width, height) ?: return null
        cache.put(key, decoded)
        return decoded
    }

    fun evict(uri: String) {
        val snapshot = cache.snapshot()
        for (key in snapshot.keys) {
            if (key.startsWith("$uri|")) cache.remove(key)
        }
    }

    fun clear() = cache.evictAll()

    private fun decode(uri: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only pass returns null by design; only the stream itself is checked here.
        try {
            val boundsStream = openStream(parsed) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val raw = try {
            val fullStream = openStream(parsed) ?: return null
            fullStream.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: OutOfMemoryError) {
            cache.trimToSize(cache.size() / 2)
            null
        } catch (e: Exception) {
            null
        } ?: return null

        val oriented = applyExif(parsed, raw)
        val maxWidth = targetWidth * 2
        val maxHeight = targetHeight * 2
        if (oriented.width <= maxWidth && oriented.height <= maxHeight) return oriented
        val scale = min(
            maxWidth.toFloat() / oriented.width,
            maxHeight.toFloat() / oriented.height,
        )
        val scaledWidth = max(1, (oriented.width * scale).toInt())
        val scaledHeight = max(1, (oriented.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(oriented, scaledWidth, scaledHeight, true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun applyExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            openStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (e: Exception) {
            null
        } ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    companion object {
        private const val MAX_DIMENSION = 4096

        fun defaultCacheBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 8).toInt().coerceIn(6 * 1024 * 1024, 48 * 1024 * 1024)
        }
    }
}
