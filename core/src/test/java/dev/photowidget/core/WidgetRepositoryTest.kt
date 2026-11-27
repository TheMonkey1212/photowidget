package dev.photowidget.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photowidget.core.data.WidgetRepository
import dev.photowidget.core.db.PhotoWidgetDatabase
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetRepositoryTest {

    private lateinit var db: PhotoWidgetDatabase
    private lateinit var repository: WidgetRepository
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotoWidgetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WidgetRepository(db) { now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun source(locator: String, addedAt: Long, type: PhotoSourceType = PhotoSourceType.PHOTO) =
        PhotoSource(type = type, locator = locator, label = locator, addedAt = addedAt)

    private fun config(
        id: Int,
        mode: WidgetMode = WidgetMode.SEQUENTIAL,
        interval: Long = 93,
        sources: List<PhotoSource> = listOf(source("content://a", 1)),
    ) = WidgetConfig(
        widgetId = id,
        name = "Widget $id",
        mode = mode,
        intervalSeconds = interval,
        tapAction = TapAction.SHUFFLE,
        sources = sources,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun record(widgetId: Int, photo: PhotoRef) {
        repository.recordSelection(
            widgetId = widgetId,
            photos = listOf(photo),
            mode = WidgetMode.SEQUENTIAL,
            cycleId = 1,
            startedNewCycle = false,
            shownAt = now,
            nextUpdateAt = now + 93_000L,
        )
    }

    @Test
    fun savesAndReloadsConfigurationAndSources() = runTest {
        repository.saveConfig(
            config(
                id = 1,
                sources = listOf(
                    source("content://a", 1),
                    source("content://album", 2, PhotoSourceType.ALBUM),
                    source("content://tree", 3, PhotoSourceType.FOLDER),
                ),
            ),
        )

        val loaded = repository.getWidget(1)!!
        assertEquals("Widget 1", loaded.name)
        assertEquals(WidgetMode.SEQUENTIAL, loaded.mode)
        assertEquals(93, loaded.intervalSeconds)
        assertEquals(TapAction.SHUFFLE, loaded.tapAction)
        assertEquals(
            listOf("content://a", "content://album", "content://tree"),
            loaded.sources.map { it.locator },
        )
        assertEquals(
            PhotoSourceType.FOLDER,
            loaded.sources.last().type,
        )
    }

    @Test
    fun savingReplacesTheSourceList() = runTest {
        repository.saveConfig(config(id = 1, sources = listOf(source("a", 1), source("b", 2))))
        repository.saveConfig(config(id = 1, sources = listOf(source("b", 2))))
        assertEquals(listOf("b"), repository.getWidget(1)!!.sources.map { it.locator })
    }

    @Test
    fun widgetsStayIndependent() = runTest {
        repository.saveConfig(config(id = 1, sources = listOf(source("a", 1))))
        repository.saveConfig(
            config(
                id = 2,
                mode = WidgetMode.RANDOM,
                interval = 3600,
                sources = listOf(source("b", 1)),
            ),
        )

        repository.recordSelection(
            widgetId = 1,
            photos = listOf(PhotoRef(uri = "a", key = "a")),
            mode = WidgetMode.SEQUENTIAL,
            cycleId = 1,
            startedNewCycle = false,
            shownAt = now,
            nextUpdateAt = now + 93_000,
        )

        assertEquals(WidgetMode.SEQUENTIAL, repository.getWidget(1)!!.mode)
        assertEquals(WidgetMode.RANDOM, repository.getWidget(2)!!.mode)
        assertEquals(3600, repository.getWidget(2)!!.intervalSeconds)
        assertEquals("a", repository.getWidget(1)!!.lastPhotoKey)
        assertNull(repository.getWidget(2)!!.lastPhotoKey)
        assertEquals(1, repository.historyCount(1))
        assertEquals(0, repository.historyCount(2))
    }

    @Test
    fun selectionIsRecordedInHistoryAndStats() = runTest {
        repository.saveConfig(config(id = 1))
        repository.recordSelection(
            widgetId = 1,
            photos = listOf(
                PhotoRef(uri = "a", key = "a", displayName = "Alpha"),
                PhotoRef(uri = "b", key = "b", displayName = "Beta"),
            ),
            mode = WidgetMode.COLLAGE,
            cycleId = 2,
            startedNewCycle = true,
            shownAt = now,
            nextUpdateAt = now + 93_000,
            pruneKeys = listOf("a", "b"),
        )

        val loaded = repository.getWidget(1)!!
        assertEquals("a", loaded.lastPhotoKey)
        assertEquals(2, loaded.cycleId)
        assertEquals(now + 93_000, loaded.nextUpdateAt)

        val stats = repository.getStats(1)
        assertEquals(setOf("a", "b"), stats.keys)
        assertEquals(1, stats["a"]!!.shownCount)
        assertEquals(1, stats["b"]!!.shownCount)
        assertTrue(stats["a"]!!.shownInCycle)

        val history = repository.getHistory(1)
        assertEquals(2, history.size)
        assertEquals("Alpha", history.first { it.photoKey == "a" }.displayName)
        assertTrue(history.all { it.collage })
    }

    @Test
    fun duplicateCopiesConfigurationButNotHistory() = runTest {
        repository.saveConfig(
            config(
                id = 10,
                mode = WidgetMode.SMART_SHUFFLE,
                sources = listOf(source("a", 1), source("b", 2)),
            ),
        )
        repository.recordSelection(
            widgetId = 10,
            photos = listOf(PhotoRef(uri = "a", key = "a")),
            mode = WidgetMode.SMART_SHUFFLE,
            cycleId = 1,
            startedNewCycle = false,
            shownAt = now,
            nextUpdateAt = now + 1_000,
        )

        val copy = repository.duplicateWidget(10, 11)!!
        assertEquals(11, copy.widgetId)
        assertEquals("Widget 10 copy", copy.name)
        assertEquals(WidgetMode.SMART_SHUFFLE, copy.mode)
        assertEquals(listOf("a", "b"), copy.sources.map { it.locator })
        assertNull(copy.lastPhotoKey)
        assertEquals(0L, copy.nextUpdateAt)
        assertEquals(0, repository.historyCount(11))
    }

    @Test
    fun resetHistoryClearsStatsAndRotationState() = runTest {
        repository.saveConfig(config(id = 1))
        repository.recordSelection(
            widgetId = 1,
            photos = listOf(PhotoRef(uri = "a", key = "a")),
            mode = WidgetMode.SEQUENTIAL,
            cycleId = 4,
            startedNewCycle = false,
            shownAt = now,
            nextUpdateAt = now + 5_000,
        )
        repository.resetHistory(1)

        assertEquals(0, repository.historyCount(1))
        assertTrue(repository.getStats(1).isEmpty())
        val loaded = repository.getWidget(1)!!
        assertNull(loaded.lastPhotoKey)
        // Reset clears rotation state but keeps the already scheduled refresh.
        assertEquals(now + 5_000, loaded.nextUpdateAt)
        assertEquals(1L, loaded.cycleId)
        assertEquals(0L, loaded.sequence)
    }

    @Test
    fun previousPhotoWorksBeforeAnyCurrentKeyExists() = runTest {
        repository.saveConfig(config(1))
        record(1, PhotoRef(uri = "content://a", key = "a"))
        assertEquals("a", repository.previousPhoto(1, null)?.key)

        now += 1_000
        record(1, PhotoRef(uri = "content://b", key = "b"))
        assertEquals("a", repository.previousPhoto(1, "b")?.key)
        assertEquals("b", repository.previousPhoto(1, null)?.key)
    }

    @Test
    fun duplicatingOverAnEmptyStubKeepsTheSources() = runTest {
        repository.saveConfig(
            config(1, sources = listOf(source("content://album", 1, PhotoSourceType.ALBUM))),
        )
        // The launcher pinned the copy first, so an untouched row already exists.
        repository.ensureWidget(2)

        val copy = repository.duplicateWidget(1, 2)!!

        assertEquals(listOf("content://album"), copy.sources.map { it.locator })
        assertEquals(listOf("content://album"), repository.getWidget(2)!!.sources.map { it.locator })
    }

    @Test
    fun deletingAWidgetRemovesItsSourcesAndHistory() = runTest {
        repository.saveConfig(config(id = 1))
        repository.recordSelection(
            widgetId = 1,
            photos = listOf(PhotoRef(uri = "a", key = "a")),
            mode = WidgetMode.SEQUENTIAL,
            cycleId = 1,
            startedNewCycle = false,
            shownAt = now,
            nextUpdateAt = now + 1_000,
        )

        repository.deleteWidget(1)

        assertNull(repository.getWidget(1))
        assertTrue(repository.getSources(1).isEmpty())
        assertEquals(0, repository.historyCount(1))
    }
}
