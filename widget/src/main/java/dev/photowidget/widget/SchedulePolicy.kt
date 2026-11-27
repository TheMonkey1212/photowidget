package dev.photowidget.widget

import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetLimits
import java.time.Instant
import java.time.ZoneId

/**
 * Pure scheduling rules, unit tested independently from AlarmManager.
 *
 * Android never guarantees exact background delivery: alarms can be batched and
 * Doze can delay them (allow-while-idle alarms are rate limited to roughly one
 * per nine minutes per app). These rules describe the *desired* time; the
 * scheduler, watchdog and boot receiver keep trying to honour it.
 */
object SchedulePolicy {

    /** A small offset after midnight so the "new day" is unambiguous. */
    const val DAILY_GRACE_MS = 5_000L

    fun localEpochDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Next time this widget should refresh. 0 means "no timer" (single-photo mode).
     */
    fun nextUpdateAt(now: Long, config: WidgetConfig, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (config.isManual) return 0L
        if (config.mode.resetsAtMidnight) {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return nextMidnight + DAILY_GRACE_MS
        }
        val interval = config.intervalSeconds.coerceAtLeast(WidgetLimits.MIN_INTERVAL_SECONDS)
        return now + interval * 1000L
    }

    fun isDue(now: Long, nextUpdateAt: Long): Boolean = nextUpdateAt in 1..now
}
