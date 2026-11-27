package dev.photowidget.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Battery-friendly safety net: every 30 minutes it repairs missing alarms and
 * refreshes widgets whose planned update was missed (for example after Doze, a
 * force stop, or an OEM killing the alarm).
 */
class WidgetWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        WidgetRuntime.get(applicationContext).coordinator.catchUp()
        Result.success()
    } catch (e: Throwable) {
        Log.w("PhotoWidget", "Watchdog failed: ${e.message}")
        Result.retry()
    }
}
