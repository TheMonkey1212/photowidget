package dev.photowidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Alarms do not survive a reboot, so every widget is re-armed here.
 * Package updates and clock/timezone changes also need a refresh.
 */
class WidgetBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }
        val pending = goAsync()
        WidgetRuntime.launch(context) { graph ->
            try {
                withTimeoutOrNull(9_000) {
                    graph.scheduler.ensureWatchdog()
                    graph.coordinator.catchUp()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
