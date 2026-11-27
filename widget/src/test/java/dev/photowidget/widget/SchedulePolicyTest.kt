package dev.photowidget.widget

import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SchedulePolicyTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val noon = 1_700_000_000_000L

    @Test
    fun intervalUsesExactSeconds() {
        val config = WidgetConfig(
            widgetId = 1,
            mode = WidgetMode.SEQUENTIAL,
            intervalSeconds = 93,
        )
        assertEquals(noon + 93_000L, SchedulePolicy.nextUpdateAt(noon, config, zone))
    }

    @Test
    fun dailyWaitsForNextLocalMidnight() {
        val config = WidgetConfig(widgetId = 1, mode = WidgetMode.DAILY)
        val next = SchedulePolicy.nextUpdateAt(noon, config, zone)
        val expectedDay = Instant.ofEpochMilli(noon).atZone(zone).toLocalDate().plusDays(1)
        val expected = expectedDay.atStartOfDay(zone).toInstant().toEpochMilli() +
            SchedulePolicy.DAILY_GRACE_MS
        assertEquals(expected, next)
        assertTrue(next > noon)
    }

    @Test
    fun dayOfWeekAlsoWaitsForNextLocalMidnight() {
        val daily = WidgetConfig(widgetId = 1, mode = WidgetMode.DAILY)
        val weekly = WidgetConfig(widgetId = 1, mode = WidgetMode.WEEKLY)
        assertEquals(
            SchedulePolicy.nextUpdateAt(noon, daily, zone),
            SchedulePolicy.nextUpdateAt(noon, weekly, zone),
        )
    }

    @Test
    fun singlePhotoModeHasNoTimer() {
        val config = WidgetConfig(widgetId = 1, mode = WidgetMode.SINGLE)
        assertEquals(0L, SchedulePolicy.nextUpdateAt(noon, config, zone))
    }

    @Test
    fun dueAndFutureTimestamps() {
        assertTrue(SchedulePolicy.isDue(1000L, 1000L))
        assertTrue(SchedulePolicy.isDue(1000L, 999L))
        assertFalse(SchedulePolicy.isDue(1000L, 1001L))
        assertFalse(SchedulePolicy.isDue(1000L, 0L))
    }
}
