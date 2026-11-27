package dev.photowidget.app

import dev.photowidget.model.WidgetLimits
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Maps the interval slider (0..1000) to seconds on a logarithmic scale, so both
 * 10 seconds and one week are reachable and short intervals get useful
 * resolution. Exact values are entered numerically, not chosen from presets.
 */
object IntervalMath {

    const val STEPS = 1000

    fun multiplier(unitIndex: Int): Long = when (unitIndex) {
        0 -> 1L
        1 -> 60L
        else -> 3600L
    }

    fun bestUnit(seconds: Long): Int = when {
        seconds >= 3600 && seconds % 3600 == 0L -> 2
        seconds >= 60 && seconds % 60 == 0L -> 1
        else -> 0
    }

    /**
     * Seconds for [value] of [unitIndex], or null when that is outside the supported
     * range. Used when the unit changes so the number the user typed keeps its meaning.
     */
    fun secondsOrNull(value: Long, unitIndex: Int): Long? {
        if (value <= 0L) return null
        val multiplier = multiplier(unitIndex)
        if (value > WidgetLimits.MAX_INTERVAL_SECONDS / multiplier) return null
        val total = value * multiplier
        return total.takeIf { it in WidgetLimits.MIN_INTERVAL_SECONDS..WidgetLimits.MAX_INTERVAL_SECONDS }
    }

    /**
     * The number shown in the value field for [seconds] in [unitIndex].
     *
     * Rounded up, never down: displaying 1 for 93 seconds would silently turn the
     * interval into 60 seconds the next time the field was read.
     */
    fun displayValue(seconds: Long, unitIndex: Int): Long {
        val multiplier = multiplier(unitIndex)
        val clamped = seconds.coerceIn(
            WidgetLimits.MIN_INTERVAL_SECONDS,
            WidgetLimits.MAX_INTERVAL_SECONDS,
        )
        return ((clamped + multiplier - 1) / multiplier).coerceAtLeast(1L)
    }

    fun progressOf(seconds: Long): Int {
        val min = WidgetLimits.MIN_INTERVAL_SECONDS.toDouble()
        val max = WidgetLimits.MAX_INTERVAL_SECONDS.toDouble()
        val clamped = seconds.coerceIn(WidgetLimits.MIN_INTERVAL_SECONDS, WidgetLimits.MAX_INTERVAL_SECONDS)
        val ratio = ln(clamped / min) / ln(max / min)
        return (ratio * STEPS).roundToInt().coerceIn(0, STEPS)
    }

    fun secondsOf(progress: Int): Long {
        val min = WidgetLimits.MIN_INTERVAL_SECONDS.toDouble()
        val max = WidgetLimits.MAX_INTERVAL_SECONDS.toDouble()
        val value = min * (max / min).pow(progress.coerceIn(0, STEPS) / STEPS.toDouble())
        return value.roundToLong().coerceIn(
            WidgetLimits.MIN_INTERVAL_SECONDS,
            WidgetLimits.MAX_INTERVAL_SECONDS,
        )
    }
}
