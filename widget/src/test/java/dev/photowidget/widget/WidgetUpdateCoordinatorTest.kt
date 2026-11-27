package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.photowidget.core.data.AppPreferences
import dev.photowidget.core.data.WidgetRepository
import dev.photowidget.core.db.PhotoWidgetDatabase
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceProvider
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.RenderQuality
import dev.photowidget.model.ResolvedPhotos
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import dev.photowidget.photos.PhotoImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetUpdateCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: PhotoWidgetDatabase
    private lateinit var repository: WidgetRepository
    private lateinit var publisher: FakePublisher
    private lateinit var scheduler: FakeScheduler
    private lateinit var resolver: FakeResolver
    private lateinit var preferences: AppPreferences
    private lateinit var coordinator: WidgetUpdateCoordinator
    private var now = 1_700_000_000_000L

    private class FakePublisher(var rejectFirst: Boolean = false) : WidgetPublisher {
        var calls = 0
        var lastViews: RemoteViews? = null

        override fun publish(widgetId: Int, views: RemoteViews): Boolean {
            calls += 1
            lastViews = views
            return !(rejectFirst && calls == 1)
        }
    }

    private class FakeScheduler : WidgetSchedulerApi {
        val scheduled = mutableMapOf<Int, Long>()
        val cancelled = mutableSetOf<Int>()
        var watchdog = false

        override fun schedule(widgetId: Int, triggerAt: Long) {
            scheduled[widgetId] = triggerAt
            cancelled.remove(widgetId)
        }

        override fun cancel(widgetId: Int) {
            scheduled.remove(widgetId)
            cancelled += widgetId
        }

        override fun canUseExactAlarms(): Boolean = true

        override fun ensureWatchdog() {
            watchdog = true
        }

        override fun cancelWatchdog() {
            watchdog = false
        }
    }

    private class FakeResolver : PhotoSourceProvider {
        var photos: List<PhotoRef> = emptyList()

        override fun resolve(sources: List<PhotoSource>): ResolvedPhotos =
            ResolvedPhotos(photos = photos)
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhotoWidgetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WidgetRepository(db) { now }
        publisher = FakePublisher()
        scheduler = FakeScheduler()
        resolver = FakeResolver()
        val loader = object : PhotoImageLoader {
            override fun load(
                uri: String,
                targetWidth: Int,
                targetHeight: Int,
                quality: RenderQuality,
            ): Bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
            }
        }
        preferences = AppPreferences(context)
        preferences.deletedWidgetIds = emptySet()
        coordinator = WidgetUpdateCoordinator(
            context = context,
            preferences = preferences,
            repository = repository,
            resolver = resolver,
            renderer = WidgetRenderer(context, loader),
            viewFactory = WidgetViewFactory(context, AppPreferences(context)),
            publisher = publisher,
            scheduler = scheduler,
            sizeProvider = { WidgetSize(300, 300) },
            clock = { now },
            zone = ZoneId.of("UTC"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ref(uri: String) = PhotoRef(uri = uri, key = uri, displayName = uri)

    private suspend fun saveWidget(
        id: Int,
        mode: WidgetMode,
        uris: List<String> = emptyList(),
        interval: Long = 93,
        singlePhoto: String? = null,
    ) {
        repository.saveConfig(
            WidgetConfig(
                widgetId = id,
                name = "Widget $id",
                mode = mode,
                intervalSeconds = interval,
                singlePhotoUri = singlePhoto,
                sources = uris.mapIndexed { index, uri ->
                    PhotoSource(
                        type = PhotoSourceType.ALBUM,
                        locator = "bucket-$id",
                        label = uri,
                        addedAt = now + index,
                    )
                },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun sequentialAdvancesAndPersistsWithExactInterval() = runBlocking {
        saveWidget(1, WidgetMode.SEQUENTIAL, listOf("a", "b", "c"))
        resolver.photos = listOf(ref("a"), ref("b"), ref("c"))

        val first = coordinator.update(1, UpdateReason.MANUAL)
        assertEquals(listOf("a"), first.pickedKeys)
        assertEquals(now + 93_000L, first.nextUpdateAt)
        assertEquals(now + 93_000L, scheduler.scheduled[1])
        assertNotNull(publisher.lastViews)
        assertEquals(1, publisher.calls)

        now += 93_000L
        val second = coordinator.update(1, UpdateReason.SCHEDULED)
        assertEquals(listOf("b"), second.pickedKeys)

        val config = repository.getWidget(1)!!
        assertEquals("b", config.lastPhotoKey)
        assertEquals(now + 93_000L, config.nextUpdateAt)
        assertEquals(2, repository.historyCount(1))
        assertEquals(1, repository.getStats(1)["b"]!!.shownCount)
    }

    @Test
    fun redrawOnResizeDoesNotAdvanceTheRotation() = runBlocking {
        saveWidget(1, WidgetMode.SEQUENTIAL, listOf("a", "b"))
        resolver.photos = listOf(ref("a"), ref("b"))
        coordinator.update(1, UpdateReason.MANUAL)

        now += 5_000L
        val redraw = coordinator.update(1, UpdateReason.RESIZE)

        assertEquals(listOf("a"), redraw.pickedKeys)
        assertEquals("a", repository.getWidget(1)!!.lastPhotoKey)
        assertEquals(1, repository.historyCount(1))
    }

    @Test
    fun widgetWithoutSourcesPublishesPlaceholderAndStopsAlarm() = runBlocking {
        saveWidget(1, WidgetMode.SMART_SHUFFLE)
        val outcome = coordinator.update(1, UpdateReason.SCHEDULED)

        assertTrue(outcome.placeholder)
        assertEquals(1, publisher.calls)
        assertTrue(scheduler.cancelled.contains(1))
        assertEquals(0L, repository.getWidget(1)!!.nextUpdateAt)
    }

    @Test
    fun singlePhotoModeRunsManuallyWithoutAlarm() = runBlocking {
        saveWidget(1, WidgetMode.SINGLE, singlePhoto = "content://one")
        val outcome = coordinator.update(1, UpdateReason.MANUAL)

        assertTrue(outcome.hasPhoto)
        assertEquals(listOf("content://one"), outcome.pickedKeys)
        assertTrue(scheduler.cancelled.contains(1))
        assertFalse(scheduler.scheduled.containsKey(1))
        assertEquals(0L, repository.getWidget(1)!!.nextUpdateAt)
        assertEquals("content://one", repository.getWidget(1)!!.singlePhotoUri)
    }

    @Test
    fun deletedPhotoDoesNotStallRotation() = runBlocking {
        saveWidget(1, WidgetMode.SEQUENTIAL, listOf("a", "b", "c"))
        resolver.photos = listOf(ref("a"), ref("b"), ref("c"))
        coordinator.update(1, UpdateReason.MANUAL)

        resolver.photos = listOf(ref("b"), ref("c"))
        now += 93_000L
        val outcome = coordinator.update(1, UpdateReason.SCHEDULED)

        assertTrue(outcome.hasPhoto)
        assertFalse(outcome.pickedKeys.contains("a"))
        assertEquals(outcome.pickedKeys.first(), repository.getWidget(1)!!.lastPhotoKey)
        assertEquals(2, repository.historyCount(1))
    }

    @Test
    fun twoWidgetsRotateIndependently() = runBlocking {
        saveWidget(1, WidgetMode.SEQUENTIAL, listOf("a", "b", "c"))
        saveWidget(2, WidgetMode.SEQUENTIAL, listOf("a", "b", "c"))
        resolver.photos = listOf(ref("a"), ref("b"), ref("c"))

        coordinator.update(1, UpdateReason.MANUAL)
        now += 93_000L
        coordinator.update(1, UpdateReason.SCHEDULED)
        coordinator.update(2, UpdateReason.MANUAL)

        assertEquals("b", repository.getWidget(1)!!.lastPhotoKey)
        assertEquals("a", repository.getWidget(2)!!.lastPhotoKey)
        assertEquals(2, repository.historyCount(1))
        assertEquals(1, repository.historyCount(2))
        assertTrue(scheduler.scheduled.containsKey(1))
        assertTrue(scheduler.scheduled.containsKey(2))
    }

    @Test
    fun rejectedRemoteViewsIsRetriedAtSmallerSize() = runBlocking {
        publisher.rejectFirst = true
        saveWidget(1, WidgetMode.SEQUENTIAL, listOf("a"))
        resolver.photos = listOf(ref("a"))

        val outcome = coordinator.update(1, UpdateReason.MANUAL)

        assertTrue(outcome.published)
        assertEquals(2, publisher.calls)
        assertTrue(outcome.hasPhoto)
    }

    @Test
    fun tapForADeletedWidgetDoesNotResurrectIt() = runBlocking {
        val outcome = coordinator.update(42, UpdateReason.TAP_NEXT)

        assertTrue(outcome.placeholder)
        assertNull("a stale tap must not create a ghost widget row", repository.getWidget(42))
        assertTrue(scheduler.cancelled.contains(42))
    }

    @Test
    fun deletedWidgetStaysDeletedWhileTheLauncherStillHostsIt() = runBlocking {
        saveWidget(9, WidgetMode.SEQUENTIAL, listOf("a"))
        resolver.photos = listOf(ref("a"))
        coordinator.update(9, UpdateReason.MANUAL)

        coordinator.deleteWidget(9)
        val later = coordinator.update(9, UpdateReason.LAUNCHER_UPDATE)

        assertTrue(later.placeholder)
        assertNull("a deleted widget must not come back", repository.getWidget(9))
        assertTrue(scheduler.cancelled.contains(9))

        // Configuring the id again (or pinning a new widget) revives it.
        preferences.clearDeletedWidget(9)
        coordinator.update(9, UpdateReason.LAUNCHER_UPDATE)
        assertNotNull(repository.getWidget(9))
    }

    @Test
    fun pinningAnotherWidgetKeepsTheDeletedMarkerOfTheOldOne() = runBlocking {
        saveWidget(9, WidgetMode.SEQUENTIAL, listOf("a"))
        resolver.photos = listOf(ref("a"))
        coordinator.deleteWidget(9)

        // Adding or configuring a new widget must not clear the markers of widgets the
        // user deleted: a late launcher update would otherwise recreate their rows.
        saveWidget(10, WidgetMode.SEQUENTIAL, listOf("a"))
        preferences.clearDeletedWidget(10)

        assertTrue("deleting one widget must not forget another", 9 in preferences.deletedWidgetIds)
        val later = coordinator.update(9, UpdateReason.LAUNCHER_UPDATE)
        assertTrue(later.placeholder)
        assertNull("a late launcher update must not revive widget 9", repository.getWidget(9))
    }

    @Test
    fun launcherUnbindClearsOnlyTheUnboundId() = runBlocking {
        // This is what PhotoWidgetProvider.onDeleted does for a widget the user removed
        // from the home screen: the id becomes allocatable again, other markers stay.
        preferences.markWidgetDeleted(6)
        preferences.markWidgetDeleted(7)

        preferences.clearDeletedWidget(6)

        assertEquals(setOf(7), preferences.deletedWidgetIds)
    }

    @Test
    fun launcherUpdateStillCreatesTheRowForANewlyPinnedWidget() = runBlocking {
        val outcome = coordinator.update(7, UpdateReason.LAUNCHER_UPDATE)

        assertTrue(outcome.placeholder)
        assertNotNull(repository.getWidget(7))
    }
}
