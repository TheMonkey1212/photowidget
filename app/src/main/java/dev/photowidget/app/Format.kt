package dev.photowidget.app

import android.content.Context
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import java.text.DateFormat
import java.util.Date

/** Compact, human readable time formats shared by the widget list and settings. */
object Format {

    fun duration(seconds: Long): String {
        val value = seconds.coerceAtLeast(1)
        return when {
            value < 60 -> "$value s"
            value < 3600 -> {
                val minutes = value / 60
                val rest = value % 60
                if (rest == 0L) "$minutes min" else "$minutes min $rest s"
            }

            else -> {
                val hours = value / 3600
                val minutes = (value % 3600) / 60
                if (minutes == 0L) "$hours h" else "$hours h $minutes min"
            }
        }
    }

    fun nextUpdate(nextAt: Long, now: Long = System.currentTimeMillis()): String = when {
        nextAt <= 0L -> "No timer"
        nextAt <= now -> "due now"
        nextAt - now < 60_000L -> "in ${((nextAt - now + 999L) / 1000L)} s"
        nextAt - now < 3_600_000L -> "in ${(nextAt - now) / 60_000L} min"
        nextAt - now < 48 * 3_600_000L -> "in ${(nextAt - now) / 3_600_000L} h"
        else -> "in ${(nextAt - now) / 86_400_000L} days"
    }

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun widgetSummary(context: Context, config: WidgetConfig): String {
        val mode = Labels.mode(context, config.mode)
        val timing = when (config.mode) {
            WidgetMode.DAILY, WidgetMode.WEEKLY -> "every day"
            WidgetMode.SINGLE -> "manual"
            else -> "every ${duration(config.intervalSeconds)}"
        }
        val sources = when (config.sources.size) {
            0 -> context.getString(R.string.sources_none)
            1 -> config.sources.first().label.ifBlank { "1 source" }
            else -> context.resources.getQuantityString(
                R.plurals.sources_summary,
                config.sources.size,
                config.sources.size,
            )
        }
        return "$mode · $timing · $sources"
    }
}
