package dev.photowidget.engine

import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoStat
import dev.photowidget.model.WidgetMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSelectorTest {

    private val selector = ModeSelector()
    private val photos: List<PhotoRef> = (1..5).map {
        PhotoRef(uri = "content://media/photo/$it", key = "p$it", displayName = "Photo $it")
    }

    private fun request(
        mode: WidgetMode,
        candidates: List<PhotoRef> = photos,
        lastKey: String? = null,
        direction: StepDirection = StepDirection.FORWARD,
        forcedKey: String? = null,
        singlePhotoUri: String? = null,
        forceShuffle: Boolean = false,
        stats: Map<String, PhotoStat> = emptyMap(),
        day: Long = 20_000L,
        lastDay: Long = 20_000L,
        collageCount: Int = 4,
        seed: Int = 11,
    ) = ModeRequest(
        mode = mode,
        candidates = candidates,
        stats = stats,
        cycleId = 3,
        cooldownPercent = 20,
        intervalSeconds = 60,
        collageCount = collageCount,
        lastPhotoKey = lastKey,
        singlePhotoUri = singlePhotoUri,
        now = 1_700_000_000_000,
        localEpochDay = day,
        lastDailyEpochDay = lastDay,
        direction = direction,
        forceShuffle = forceShuffle,
        forcedKey = forcedKey,
        random = Random(seed),
    )

    @Test
    fun `empty source list is handled`() {
        val result = selector.select(request(WidgetMode.SMART_SHUFFLE, candidates = emptyList()))
        assertTrue(result.isEmpty)
        assertNull(result.primaryKey)
        assertEquals(SelectionReason.EMPTY, result.reason)
    }

    @Test
    fun `sequential walks forward and wraps`() {
        assertEquals("p3", selector.select(request(WidgetMode.SEQUENTIAL, lastKey = "p2")).primaryKey)
        assertEquals("p1", selector.select(request(WidgetMode.SEQUENTIAL, lastKey = "p5")).primaryKey)
        assertEquals("p1", selector.select(request(WidgetMode.SEQUENTIAL, lastKey = null)).primaryKey)
        assertEquals(
            SelectionReason.FORWARD,
            selector.select(request(WidgetMode.SEQUENTIAL, lastKey = "p2")).reason,
        )
    }

    @Test
    fun `single mode reuses the same photo instead of recording it again`() {
        val repeat = selector.select(
            request(
                WidgetMode.SINGLE,
                candidates = listOf(photos.first()),
                singlePhotoUri = "p1",
                lastKey = "p1",
            ),
        )
        assertEquals(listOf("p1"), repeat.keys)
        assertTrue(repeat.reusedCurrent)
        assertEquals(SelectionReason.SINGLE, repeat.reason)

        val first = selector.select(
            request(
                WidgetMode.SINGLE,
                candidates = listOf(photos.first()),
                singlePhotoUri = "p1",
                lastKey = null,
            ),
        )
        assertFalse(first.reusedCurrent)
    }

    @Test
    fun `sequential walks backward and wraps`() {
        assertEquals(
            "p1",
            selector.select(
                request(WidgetMode.SEQUENTIAL, lastKey = "p2", direction = StepDirection.BACKWARD),
            ).primaryKey,
        )
        assertEquals(
            "p5",
            selector.select(
                request(WidgetMode.SEQUENTIAL, lastKey = "p1", direction = StepDirection.BACKWARD),
            ).primaryKey,
        )
        assertEquals(
            SelectionReason.BACKWARD,
            selector.select(
                request(WidgetMode.SEQUENTIAL, lastKey = "p2", direction = StepDirection.BACKWARD),
            ).reason,
        )
    }

    @Test
    fun `sequential recovers when the last photo disappeared`() {
        assertEquals("p1", selector.select(request(WidgetMode.SEQUENTIAL, lastKey = "gone")).primaryKey)
    }

    @Test
    fun `random avoids an immediate repeat`() {
        repeat(200) { seed ->
            val result = selector.select(request(WidgetMode.RANDOM, lastKey = "p2", seed = seed))
            assertEquals(1, result.keys.size)
            assertNotEquals("p2", result.primaryKey)
        }
    }

    @Test
    fun `random still works with one photo`() {
        val result = selector.select(request(WidgetMode.RANDOM, candidates = photos.take(1), lastKey = "p1"))
        assertEquals("p1", result.primaryKey)
    }

    @Test
    fun `daily keeps showing the same photo all day`() {
        val first = selector.select(request(WidgetMode.DAILY, lastKey = "p2", lastDay = 20_000L, day = 20_000L))
        assertEquals("p2", first.primaryKey)
        assertTrue(first.reusedCurrent)
        assertEquals(SelectionReason.DAILY_REUSE, first.reason)
    }

    @Test
    fun `daily picks a new photo on a new day`() {
        val first = selector.select(request(WidgetMode.DAILY, lastKey = "p2", lastDay = 19_999L, day = 20_000L))
        assertEquals(1, first.keys.size)
        assertEquals(SelectionReason.DAILY, first.reason)
        assertTrue(!first.reusedCurrent)
    }

    @Test
    fun `daily picks anew when the current photo disappeared`() {
        val result = selector.select(request(WidgetMode.DAILY, lastKey = "gone"))
        assertEquals(SelectionReason.DAILY, result.reason)
    }

    @Test
    fun `single mode uses the manual photo`() {
        val result = selector.select(
            request(WidgetMode.SINGLE, singlePhotoUri = photos[3].uri),
        )
        assertEquals("p4", result.primaryKey)
        assertEquals(SelectionReason.SINGLE, result.reason)
    }

    @Test
    fun `single mode falls back to the first available photo`() {
        val result = selector.select(request(WidgetMode.SINGLE, singlePhotoUri = "content://deleted/1"))
        assertEquals("p1", result.primaryKey)
    }

    @Test
    fun `collage returns the requested number of unique photos`() {
        val result = selector.select(request(WidgetMode.COLLAGE, collageCount = 4))
        assertEquals(4, result.keys.size)
        assertEquals(4, result.keys.toSet().size)
    }

    @Test
    fun `collage never asks for more photos than exist`() {
        val result = selector.select(request(WidgetMode.COLLAGE, candidates = photos.take(2), collageCount = 9))
        assertEquals(2, result.keys.size)
        assertEquals(2, result.keys.toSet().size)
    }

    @Test
    fun `shuffle tap overrides sequential order`() {
        val result = selector.select(request(WidgetMode.SEQUENTIAL, lastKey = "p2", forceShuffle = true))
        assertEquals(1, result.keys.size)
        assertEquals(SelectionReason.SHUFFLE, result.reason)
    }

    @Test
    fun `previous from history forces that photo`() {
        val result = selector.select(
            request(WidgetMode.SMART_SHUFFLE, forcedKey = "p4"),
        )
        assertEquals("p4", result.primaryKey)
        assertEquals(SelectionReason.BACKWARD, result.reason)
    }

    @Test
    fun `smart shuffle returns one photo`() {
        val result = selector.select(request(WidgetMode.SMART_SHUFFLE))
        assertEquals(1, result.keys.size)
        assertEquals(SelectionReason.SHUFFLE, result.reason)
    }

    @Test
    fun `weekly keeps one photo all day and moves on at midnight`() {
        val first = selector.select(request(WidgetMode.WEEKLY, day = 20_000L, lastDay = 19_999L))
        assertEquals(SelectionReason.WEEKLY, first.reason)

        val sameDay = selector.select(
            request(WidgetMode.WEEKLY, lastKey = first.primaryKey, day = 20_000L, lastDay = 20_000L),
        )
        assertEquals(first.primaryKey, sameDay.primaryKey)
        assertTrue(sameDay.reusedCurrent)
        assertEquals(SelectionReason.WEEKLY_REUSE, sameDay.reason)

        val nextDay = selector.select(
            request(WidgetMode.WEEKLY, lastKey = first.primaryKey, day = 20_001L, lastDay = 20_000L),
        )
        assertNotEquals(first.primaryKey, nextDay.primaryKey)
    }

    @Test
    fun `weekly walks the whole list in order`() {
        val picks = (0L until photos.size.toLong()).map { offset ->
            selector.select(request(WidgetMode.WEEKLY, day = 20_000L + offset, lastDay = 0L)).primaryKey
        }
        assertEquals(photos.size, picks.toSet().size)
        assertEquals(photos.map { it.key }, picks)
    }

    @Test
    fun `least shown skips the photo that just appeared`() {
        val stats = mapOf(
            "p1" to PhotoStat(key = "p1", shownCount = 3, lastShownAt = 900L),
            "p2" to PhotoStat(key = "p2", shownCount = 1, lastShownAt = 800L),
            "p3" to PhotoStat(key = "p3", shownCount = 2, lastShownAt = 700L),
        )
        val result = selector.select(
            request(WidgetMode.LEAST_SHOWN, candidates = photos.take(3), lastKey = "p2", stats = stats),
        )
        assertEquals("p3", result.primaryKey)
        assertEquals(SelectionReason.LEAST_SHOWN, result.reason)
    }

    @Test
    fun `least shown prefers never seen photos`() {
        val stats = mapOf("p1" to PhotoStat(key = "p1", shownCount = 6, lastShownAt = 100L))
        val result = selector.select(request(WidgetMode.LEAST_SHOWN, lastKey = "p1", stats = stats))
        assertEquals("p2", result.primaryKey)
    }

    @Test
    fun `least shown reaches every photo before repeating one`() {
        val counts = mutableMapOf<String, Int>()
        val shown = mutableListOf<String>()
        var last: String? = null
        repeat(photos.size) {
            val key = selector.select(
                request(
                    WidgetMode.LEAST_SHOWN,
                    lastKey = last,
                    stats = counts.map { (k, v) ->
                        k to PhotoStat(key = k, shownCount = v, lastShownAt = v.toLong())
                    }.toMap(),
                ),
            ).primaryKey!!
            shown += key
            counts[key] = (counts[key] ?: 0) + 1
            last = key
        }
        assertEquals(photos.map { it.key }.toSet(), shown.toSet())
    }

    @Test
    fun `random mode is not a fixed order`() {
        val picks = (0 until 60).map { seed ->
            selector.select(request(WidgetMode.RANDOM, lastKey = "p1", seed = seed)).primaryKey
        }.toSet()
        assertTrue("random should explore the collection, got $picks", picks.size >= 3)
    }
}
