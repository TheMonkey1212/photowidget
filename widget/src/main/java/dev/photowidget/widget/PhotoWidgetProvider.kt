package dev.photowidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry point for launcher driven updates.
 *
 * The launcher calls onUpdate when a widget is added or the host restarts; that
 * redraws the current photo instead of advancing the rotation. Alarms and taps
 * advance it through [WidgetUpdateCoordinator].
 */
class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        WidgetRuntime.launch(context) { graph ->
            try {
                withTimeoutOrNull(9_000) {
                    appWidgetIds.forEach { id ->
                        graph.applyPendingDuplicate(id)
                        graph.coordinator.update(id, UpdateReason.LAUNCHER_UPDATE)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        WidgetRuntime.launch(context) { graph ->
            try {
                withTimeoutOrNull(8_000) {
                    graph.coordinator.update(appWidgetId, UpdateReason.RESIZE)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pending = goAsync()
        WidgetRuntime.launch(context) { graph ->
            try {
                withTimeoutOrNull(9_000) {
                    appWidgetIds.forEach { id ->
                        graph.coordinator.deleteWidget(id)
                        // Removed from the home screen for good, so a later widget may
                        // reuse this id.
                        graph.preferences.clearDeletedWidget(id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        WidgetRuntime.launch(context) { graph -> graph.scheduler.ensureWatchdog() }
    }

    override fun onDisabled(context: Context) {
        WidgetRuntime.launch(context) { graph -> graph.scheduler.cancelWatchdog() }
    }
}
