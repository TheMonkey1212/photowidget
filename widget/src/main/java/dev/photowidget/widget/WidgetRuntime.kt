package dev.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.net.Uri
import android.util.Log
import dev.photowidget.core.data.AppPreferences
import dev.photowidget.core.data.WidgetRepository
import dev.photowidget.core.db.PhotoWidgetDatabase
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.WidgetConfig
import dev.photowidget.photos.DocumentAccess
import dev.photowidget.photos.AndroidMediaQuery
import dev.photowidget.photos.PhotoBitmapLoader
import dev.photowidget.photos.PhotoResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency graph for the widget process.
 *
 * Everything is created lazily on first use so a broadcast receiver only pays for
 * what it needs. Tests construct [WidgetUpdateCoordinator] with fakes instead.
 */
class WidgetGraph(context: Context) {

    val appContext: Context = context.applicationContext
    val preferences = AppPreferences(appContext)
    val repository = WidgetRepository(PhotoWidgetDatabase.get(appContext))
    val mediaQuery = AndroidMediaQuery(appContext)
    val resolver = PhotoResolver(mediaQuery)
    val imageLoader = PhotoBitmapLoader(appContext)
    val renderer = WidgetRenderer(appContext, imageLoader)
    val viewFactory = WidgetViewFactory(appContext, preferences)
    val publisher = AppWidgetPublisher(appContext)
    val scheduler = WidgetScheduler(appContext)

    val coordinator = WidgetUpdateCoordinator(
        context = appContext,
        repository = repository,
        resolver = resolver,
        renderer = renderer,
        viewFactory = viewFactory,
        publisher = publisher,
        scheduler = scheduler,
        sizeProvider = { widgetId -> sizeOf(appContext, widgetId) },
    )

    /**
     * If the user asked to duplicate a widget and the launcher has just pinned the
     * new instance, copy the configuration into the new id now.
     */
    /**
     * Drops the persisted SAF grant of [treeUris] when no widget refers to them any more,
     * so removing folders cannot slowly exhaust Android's per-app grant limit.
     */
    suspend fun releaseUnusedFolderGrants(treeUris: Collection<String>) {
        if (treeUris.isEmpty()) return
        val inUse = repository.getAllWidgets()
            .flatMap { it.sources }
            .filter { it.type == PhotoSourceType.FOLDER }
            .map { it.locator }
            .toSet()
        treeUris.filter { it !in inUse }.forEach { uri ->
            runCatching { DocumentAccess.release(appContext, Uri.parse(uri)) }
        }
    }

    suspend fun applyPendingDuplicate(newWidgetId: Int) {
        val sourceId = preferences.pendingDuplicateSource
        if (sourceId <= 0) return
        val requestedAt = preferences.pendingDuplicateAt
        if (System.currentTimeMillis() - requestedAt > DUPLICATE_WINDOW_MS) {
            preferences.clearPendingDuplicate()
            return
        }
        // The launcher calls onUpdate as soon as the widget is pinned, which can create
        // an empty row before the configuration screen opens. Such a stub must not stop
        // the copy from being applied.
        if (!isUnconfiguredStub(repository.getWidget(newWidgetId), requestedAt)) return
        // A widget the user deleted must not be resurrected by a stale duplicate request.
        if (newWidgetId in preferences.deletedWidgetIds) return
        val copied = repository.duplicateWidget(sourceId, newWidgetId)
        if (copied != null) preferences.clearPendingDuplicate()
    }

    private fun sizeOf(context: Context, widgetId: Int): WidgetSize {
        val manager = AppWidgetManager.getInstance(context)
        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        return WidgetSizing.fromOptions(options, context.resources.displayMetrics.density)
    }

}

/**
 * True when [stored] is (or should be) an untouched row for a widget the user has just
 * pinned: either it does not exist yet, or the launcher created it after the duplicate
 * was requested and it still has no photos of its own.
 */
/**
 * How long after the user asks to duplicate a widget the new widget may adopt the copy.
 *
 * Kept short on purpose: the launcher gives no callback when a pin request is cancelled,
 * so a stale request must expire before the user adds an unrelated widget.
 */
const val DUPLICATE_WINDOW_MS = 3 * 60 * 1000L

fun isUnconfiguredStub(stored: WidgetConfig?, duplicateRequestedAt: Long): Boolean {
    if (stored == null) return true
    if (stored.hasSources || stored.singlePhotoUri != null) return false
    return stored.createdAt >= duplicateRequestedAt
}

object WidgetRuntime {

    @Volatile
    private var graph: WidgetGraph? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun get(context: Context): WidgetGraph =
        graph ?: synchronized(this) {
            graph ?: WidgetGraph(context.applicationContext).also { graph = it }
        }

    /** Fire-and-forget work from receivers, with errors contained. */
    fun launch(context: Context, block: suspend (WidgetGraph) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                block(get(appContext))
            } catch (e: Throwable) {
                Log.w("PhotoWidget", "Widget work failed: ${e.message}")
            }
        }
    }
}
