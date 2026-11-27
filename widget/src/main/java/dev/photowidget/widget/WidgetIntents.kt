package dev.photowidget.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.photowidget.model.TapAction

/**
 * Intents and PendingIntents shared by the widget views, alarms and receivers.
 *
 * Activities are referenced by class name so this module stays independent from
 * the UI module; the app declares both activities with matching class names.
 */
object WidgetIntents {

    const val EXTRA_WIDGET_ID = "dev.photowidget.extra.WIDGET_ID"

    const val ACTION_TICK = "dev.photowidget.action.TICK"
    const val ACTION_NEXT = "dev.photowidget.action.NEXT"
    const val ACTION_PREVIOUS = "dev.photowidget.action.PREVIOUS"
    const val ACTION_SHUFFLE = "dev.photowidget.action.SHUFFLE"
    const val ACTION_VIEW_FULLSCREEN = "dev.photowidget.action.VIEW_FULLSCREEN"
    const val ACTION_OPEN_SETTINGS = "dev.photowidget.action.OPEN_WIDGET_SETTINGS"

    const val FULLSCREEN_ACTIVITY = "dev.photowidget.app.FullscreenActivity"
    const val SETTINGS_ACTIVITY = "dev.photowidget.app.WidgetEditActivity"

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Alarm intent that refreshes one widget. */
    fun tickPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetTickReceiver::class.java)
            .setAction(ACTION_TICK)
            .putExtra(EXTRA_WIDGET_ID, widgetId)
        return PendingIntent.getBroadcast(context, widgetId, intent, FLAGS)
    }

    /** Intent fired when the user taps the widget. */
    fun tapPendingIntent(context: Context, widgetId: Int, action: TapAction): PendingIntent {
        val requestCode = widgetId * 10 + action.ordinal + 100
        return when (action) {
            TapAction.FULLSCREEN -> activityPending(
                context,
                widgetId,
                FULLSCREEN_ACTIVITY,
                ACTION_VIEW_FULLSCREEN,
                requestCode,
            )

            TapAction.OPEN_SETTINGS -> activityPending(
                context,
                widgetId,
                SETTINGS_ACTIVITY,
                ACTION_OPEN_SETTINGS,
                requestCode,
            )

            TapAction.NEXT, TapAction.PREVIOUS, TapAction.SHUFFLE -> {
                val actionName = when (action) {
                    TapAction.NEXT -> ACTION_NEXT
                    TapAction.PREVIOUS -> ACTION_PREVIOUS
                    else -> ACTION_SHUFFLE
                }
                val intent = Intent(context, WidgetTickReceiver::class.java)
                    .setAction(actionName)
                    .putExtra(EXTRA_WIDGET_ID, widgetId)
                PendingIntent.getBroadcast(context, requestCode, intent, FLAGS)
            }
        }
    }

    fun openSettingsIntent(context: Context, widgetId: Int): Intent =
        Intent(ACTION_OPEN_SETTINGS)
            .setClassName(context.packageName, SETTINGS_ACTIVITY)
            .putExtra(EXTRA_WIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activityPending(
        context: Context,
        widgetId: Int,
        className: String,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(action)
            .setClassName(context.packageName, className)
            .putExtra(EXTRA_WIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, FLAGS)
    }
}
