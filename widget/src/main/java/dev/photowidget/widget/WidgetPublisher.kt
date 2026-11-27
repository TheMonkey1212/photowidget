package dev.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews

/** Pushes a RemoteViews update to one widget instance. */
interface WidgetPublisher {
    /** Returns false when the launcher rejected the update (for example an oversized bitmap). */
    fun publish(widgetId: Int, views: RemoteViews): Boolean
}

class AppWidgetPublisher(private val context: Context) : WidgetPublisher {

    override fun publish(widgetId: Int, views: RemoteViews): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return try {
            manager.updateAppWidget(widgetId, views)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Widget $widgetId update rejected: ${e.message}")
            false
        }
    }

    fun widgetIds(): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, PhotoWidgetProvider::class.java))

    private companion object {
        const val TAG = "PhotoWidget"
    }
}
