package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Stores the last rendered frame of each widget so the app can show real widget
 * thumbnails without re-rendering on every list scroll. Lives in the app cache
 * and contains only images the user already chose for their own widget.
 */
object WidgetPreviewCache {

    private const val DIRECTORY = "widget_previews"

    fun save(context: Context, widgetId: Int, bitmap: Bitmap): Boolean = try {
        val file = file(context, widgetId)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun load(context: Context, widgetId: Int): Bitmap? = try {
        val file = file(context, widgetId)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    } catch (e: Throwable) {
        null
    }

    fun delete(context: Context, widgetId: Int) {
        runCatching { file(context, widgetId).delete() }
    }

    fun clear(context: Context) {
        runCatching {
            File(context.cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
        }
    }

    private fun file(context: Context, widgetId: Int): File =
        File(File(context.cacheDir, DIRECTORY), "widget_$widgetId.png")
}
