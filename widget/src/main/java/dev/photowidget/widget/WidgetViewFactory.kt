package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import dev.photowidget.core.data.AppPreferences
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetConfig

/**
 * Builds the RemoteViews for one widget update.
 *
 * With transitions enabled the two ImageViews alternate so launchers that honour
 * RemoteViews animations crossfade; without them the bitmap is replaced in place.
 */
class WidgetViewFactory(
    private val context: Context,
    private val preferences: AppPreferences,
) {

    fun create(
        widgetId: Int,
        bitmap: Bitmap,
        config: WidgetConfig,
        contentDescription: String,
        tapActionOverride: TapAction? = null,
        animate: Boolean = true,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_photo)
        val useTransitions = config.transitions && animate
        val child = if (useTransitions) {
            1 - preferences.lastFlipperChild(widgetId)
        } else {
            0
        }
        val imageId = if (child == 0) R.id.widget_image_a else R.id.widget_image_b

        views.setImageViewBitmap(imageId, bitmap)
        views.setViewVisibility(R.id.widget_initial_label, View.GONE)
        views.setInt(R.id.widget_flipper, "setDisplayedChild", child)
        views.setContentDescription(R.id.widget_root, contentDescription)
        views.setContentDescription(imageId, contentDescription)

        val tapAction = tapActionOverride ?: config.tapAction
        views.setOnClickPendingIntent(
            R.id.widget_root,
            WidgetIntents.tapPendingIntent(context, widgetId, tapAction),
        )
        if (useTransitions) {
            preferences.setLastFlipperChild(widgetId, child)
        }
        return views
    }
}
