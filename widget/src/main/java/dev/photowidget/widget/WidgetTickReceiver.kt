package dev.photowidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles alarm ticks and widget taps (next / previous / shuffle).
 * Tap actions that need UI use Activity PendingIntents instead.
 */
class WidgetTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(WidgetIntents.EXTRA_WIDGET_ID, -1)
        if (widgetId < 0) return
        val reason = when (intent.action) {
            WidgetIntents.ACTION_NEXT -> UpdateReason.TAP_NEXT
            WidgetIntents.ACTION_PREVIOUS -> UpdateReason.TAP_PREVIOUS
            WidgetIntents.ACTION_SHUFFLE -> UpdateReason.TAP_SHUFFLE
            WidgetIntents.ACTION_TICK -> UpdateReason.SCHEDULED
            else -> return
        }
        val pending = goAsync()
        WidgetRuntime.launch(context) { graph ->
            try {
                withTimeoutOrNull(9_000) {
                    graph.coordinator.update(widgetId, reason)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
