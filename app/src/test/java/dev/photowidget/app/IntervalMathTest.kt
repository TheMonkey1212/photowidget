package dev.photowidget.app

import dev.photowidget.model.WidgetLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalMathTest {

    @Test
    fun sliderRoundTripsExactIntervals() {
        listOf(10L, 45L, 93L, 60L, 900L, 3600L, 5400L, 86_400L, 604_800L).forEach { seconds ->
            val restored = IntervalMath.secondsOf(IntervalMath.progressOf(seconds))
            val tolerance = (seconds * 0.01).coerceAtLeast(2.0)
            assertTrue(
                "expected $seconds +/- $tolerance, got $restored",
                kotlin.math.abs(restored - seconds) <= tolerance,
            )
        }
    }

    @Test
    fun limitsAreClamped() {
        assertEquals(
            WidgetLimits.MIN_INTERVAL_SECONDS,
            IntervalMath.secondsOf(0),
        )
        assertEquals(
            WidgetLimits.MAX_INTERVAL_SECONDS,
            IntervalMath.secondsOf(IntervalMath.STEPS),
        )
    }

    @Test
    fun changingTheUnitKeepsTheNumberTheUserTyped() {
        // 93 typed while "minutes" is selected means 93 minutes, not 1 minute.
        assertEquals(5_580L, IntervalMath.secondsOrNull(93, 1))
        assertEquals(334_800L, IntervalMath.secondsOrNull(93, 2))
        assertEquals(93L, IntervalMath.secondsOrNull(93, 0))
        assertNull(IntervalMath.secondsOrNull(0, 0))
        assertNull(IntervalMath.secondsOrNull(9_999, 2))
    }

    @Test
    fun displayedValueNeverRepresentsLessThanTheInterval() {
        val units = 0..2
        listOf(10L, 59L, 60L, 93L, 120L, 3_600L, 5_400L, 86_400L, 604_800L).forEach { seconds ->
            units.forEach { unit ->
                val shown = IntervalMath.displayValue(seconds, unit)
                val represented = shown * IntervalMath.multiplier(unit)
                assertTrue(
                    "$seconds s shown as $shown in unit $unit ($represented s)",
                    represented >= seconds,
                )
                assertTrue(
                    "$seconds s shown as $shown in unit $unit is too coarse",
                    represented < seconds + IntervalMath.multiplier(unit),
                )
            }
        }
    }

    @Test
    fun unitsAreChosenForReadableValues() {
        assertEquals(0, IntervalMath.bestUnit(93))
        assertEquals(1, IntervalMath.bestUnit(120))
        assertEquals(1, IntervalMath.bestUnit(5400))
        assertEquals(2, IntervalMath.bestUnit(7200))
        assertEquals(60L, IntervalMath.multiplier(1))
        assertEquals(3600L, IntervalMath.multiplier(2))
    }
}
