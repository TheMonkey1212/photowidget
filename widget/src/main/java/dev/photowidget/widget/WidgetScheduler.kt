package dev.photowidget.widget

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Alarm scheduling surface, faked in unit tests. */
interface WidgetSchedulerApi {
    fun schedule(widgetId: Int, triggerAt: Long)
    fun cancel(widgetId: Int)
    fun canUseExactAlarms(): Boolean
    fun ensureWatchdog() = Unit
    fun cancelWatchdog() = Unit
}

/**
 * Schedules one alarm per widget.
 *
 * Exact alarms are used only when the user granted the special access; otherwise
 * setAndAllowWhileIdle is used, which still fires in Doze but may be delayed.
 * A 30 minute WorkManager watchdog repairs lost alarms and catches up overdue
 * widgets without keeping a service (or a notification) alive.
 */
class WidgetScheduler(private val context: Context) : WidgetSchedulerApi {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)
    private val powerManager: PowerManager =
        context.getSystemService(PowerManager::class.java)

    override fun schedule(widgetId: Int, triggerAt: Long) {
        if (triggerAt <= 0L) {
            cancel(widgetId)
            return
        }
        val trigger = triggerAt.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val pendingIntent = WidgetIntents.tickPendingIntent(context, widgetId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canUseExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                // Inexact but still allowed to wake the device; setAndAllowWhileIdle
                // exists since API 23 and minSdk is 26.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            }
        } catch (e: SecurityException) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            }
        }
    }

    override fun cancel(widgetId: Int) {
        runCatching { alarmManager.cancel(WidgetIntents.tickPendingIntent(context, widgetId)) }
    }

    override fun canUseExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)

    override fun ensureWatchdog() {
        val request = PeriodicWorkRequestBuilder<WidgetWatchdogWorker>(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WATCHDOG_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override fun cancelWatchdog() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK) }
    }

    companion object {
        const val WATCHDOG_WORK = "photo_widget_watchdog"
    }
}
