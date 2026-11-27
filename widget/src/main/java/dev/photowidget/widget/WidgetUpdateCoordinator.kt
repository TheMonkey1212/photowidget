package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import dev.photowidget.core.data.AppPreferences
import dev.photowidget.core.data.WidgetRepository
import dev.photowidget.engine.ModeRequest
import dev.photowidget.engine.ModeSelection
import dev.photowidget.engine.ModeSelector
import dev.photowidget.engine.SelectionReason
import dev.photowidget.engine.StepDirection
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSourceProvider
import dev.photowidget.model.ResolvedPhotos
import dev.photowidget.model.SourceIssue
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.min
import kotlin.random.Random

enum class UpdateReason {
    SCHEDULED,
    LAUNCHER_UPDATE,
    RESIZE,
    TAP_NEXT,
    TAP_PREVIOUS,
    TAP_SHUFFLE,
    CONFIG_CHANGED,
    MANUAL,
    BOOT,
    APP_OPENED,
}

data class UpdateOutcome(
    val widgetId: Int,
    val reason: UpdateReason,
    val pickedKeys: List<String> = emptyList(),
    val reusedCurrent: Boolean = false,
    val placeholder: Boolean = false,
    val issues: List<SourceIssue> = emptyList(),
    val nextUpdateAt: Long = 0L,
    val published: Boolean = false,
    val size: WidgetSize? = null,
) {
    val hasPhoto: Boolean get() = pickedKeys.isNotEmpty()
}

/**
 * Orchestrates one widget refresh:
 * resolve sources -> choose photo(s) -> persist rotation state -> render -> publish -> schedule.
 *
 * All widgets are handled independently and every state change is committed
 * before the next alarm is set, so a crash can at worst skip one refresh.
 */
class WidgetUpdateCoordinator(
    private val context: Context,
    private val preferences: AppPreferences = AppPreferences(context),
    private val repository: WidgetRepository,
    private val resolver: PhotoSourceProvider,
    private val renderer: WidgetRenderer,
    private val viewFactory: WidgetViewFactory,
    private val publisher: WidgetPublisher,
    private val scheduler: WidgetSchedulerApi,
    private val sizeProvider: (Int) -> WidgetSize,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val selector: ModeSelector = ModeSelector(),
) {

    suspend fun update(widgetId: Int, reason: UpdateReason): UpdateOutcome =
        withContext(Dispatchers.Default) { performUpdate(widgetId, reason) }

    /**
     * Repairs scheduling for every widget and refreshes anything overdue.
     * Called by the watchdog, boot receiver and when the app opens.
     */
    suspend fun catchUp() = withContext(Dispatchers.Default) {
        val now = clock()
        val widgets = repository.getAllWidgets()
        for (config in widgets) {
            val id = config.widgetId
            when {
                config.mode != WidgetMode.SINGLE && !config.hasSources -> {
                    repository.updateNextUpdateAt(id, 0L)
                    scheduler.cancel(id)
                }

                config.isManual -> scheduler.cancel(id)

                config.nextUpdateAt == 0L -> update(id, UpdateReason.MANUAL)

                config.nextUpdateAt <= now -> update(id, UpdateReason.SCHEDULED)

                else -> scheduler.schedule(id, config.nextUpdateAt)
            }
        }
        if (widgets.isNotEmpty()) scheduler.ensureWatchdog()
    }

    /** Redraws every widget without consuming a new rotation step (unless due). */
    suspend fun refreshAll(reason: UpdateReason = UpdateReason.APP_OPENED) = withContext(Dispatchers.Default) {
        repository.getAllWidgets().forEach { update(it.widgetId, reason) }
    }

    suspend fun deleteWidget(widgetId: Int) = withContext(Dispatchers.Default) {
        val stored = repository.getWidget(widgetId)
        scheduler.cancel(widgetId)
        preferences.markWidgetDeleted(widgetId)
        if (stored != null) {
            // The app cannot remove the widget from a launcher itself, so explain the
            // state instead of leaving the last photo frozen on the home screen.
            val measured = sizeProvider(widgetId)
            publishPlaceholder(
                widgetId,
                stored,
                WidgetSizing.clamp(measured.widthPx, measured.heightPx),
                "Widget removed",
                "Remove it from your home screen",
            )
        }
        repository.deleteWidget(widgetId)
        WidgetPreviewCache.delete(context, widgetId)
        val remaining = repository.getAllWidgets()
        if (remaining.isEmpty()) scheduler.cancelWatchdog()
    }

    private suspend fun performUpdate(widgetId: Int, reason: UpdateReason): UpdateOutcome {
        val now = clock()
        val stored = repository.getWidget(widgetId)
        val measured = sizeProvider(widgetId)
        val size = WidgetSizing.clamp(measured.widthPx, measured.heightPx)
        val config = stored ?: when {
            // Deleted in the app but still hosted by the launcher: keep it deleted and
            // only explain how to get rid of it.
            widgetId in preferences.deletedWidgetIds -> {
                val published = publishPlaceholder(
                    widgetId,
                    WidgetConfig(widgetId = widgetId),
                    size,
                    "Widget removed",
                    "Remove it from your home screen",
                )
                scheduler.cancel(widgetId)
                return UpdateOutcome(
                    widgetId = widgetId,
                    reason = reason,
                    placeholder = true,
                    published = published,
                    size = size,
                )
            }
            // The launcher is the only caller that legitimately reports an id the app has
            // not stored yet (the widget was just added). A tap or an alarm for a deleted
            // widget must not resurrect it as a ghost entry in the app's widget list.
            reason == UpdateReason.LAUNCHER_UPDATE || reason == UpdateReason.RESIZE ->
                repository.ensureWidget(widgetId)
            else -> {
                scheduler.cancel(widgetId)
                return UpdateOutcome(widgetId = widgetId, reason = reason, placeholder = true)
            }
        }
        val today = SchedulePolicy.localEpochDay(now, zone)
        val dayChanged = config.mode.resetsAtMidnight && config.lastDailyEpochDay != today

        if (config.mode != WidgetMode.SINGLE && !config.hasSources) {
            val published = publishPlaceholder(
                widgetId,
                config,
                size,
                "No photos yet",
                "Tap to choose photos",
            )
            repository.updateNextUpdateAt(widgetId, 0L)
            scheduler.cancel(widgetId)
            return UpdateOutcome(
                widgetId = widgetId,
                reason = reason,
                placeholder = true,
                published = published,
                size = size,
            )
        }

        val resolved = if (config.mode == WidgetMode.SINGLE) ResolvedPhotos() else resolver.resolve(config.sources)
        val candidates = candidatesFor(config, resolved)
        if (candidates.isEmpty()) {
            val denied = resolved.issues.any { it.permissionDenied }
            val title = when {
                config.mode == WidgetMode.SINGLE && config.singlePhotoUri == null -> "No photo chosen"
                config.mode == WidgetMode.SINGLE -> "Photo unavailable"
                denied -> "Photo access needed"
                resolved.issues.isNotEmpty() -> "Photos unavailable"
                else -> "No photos found"
            }
            val subtitle = when {
                config.mode == WidgetMode.SINGLE -> "Tap to choose a photo"
                denied -> "Tap to grant access"
                else -> "Tap to add photos"
            }
            val retryAt = if (config.mode == WidgetMode.SINGLE) {
                0L
            } else {
                now + min(config.intervalSeconds, 900L) * 1000L
            }
            val published = publishPlaceholder(widgetId, config, size, title, subtitle)
            repository.updateNextUpdateAt(widgetId, retryAt)
            if (retryAt > 0L) scheduler.schedule(widgetId, retryAt) else scheduler.cancel(widgetId)
            return UpdateOutcome(
                widgetId = widgetId,
                reason = reason,
                placeholder = true,
                issues = resolved.issues,
                nextUpdateAt = retryAt,
                published = published,
                size = size,
            )
        }

        var pickNew = when (config.mode) {
            WidgetMode.SINGLE -> reason == UpdateReason.CONFIG_CHANGED || reason == UpdateReason.MANUAL
            else -> when (reason) {
                UpdateReason.SCHEDULED,
                UpdateReason.TAP_NEXT,
                UpdateReason.TAP_PREVIOUS,
                UpdateReason.TAP_SHUFFLE,
                UpdateReason.CONFIG_CHANGED,
                UpdateReason.MANUAL,
                -> true

                UpdateReason.LAUNCHER_UPDATE,
                UpdateReason.RESIZE,
                UpdateReason.BOOT,
                UpdateReason.APP_OPENED,
                -> dayChanged
            }
        }

        var photos: List<PhotoRef> = if (pickNew) emptyList() else currentPhotos(widgetId, config, candidates)
        if (photos.isEmpty()) pickNew = true

        var selection: ModeSelection? = null
        var reused = false
        if (pickNew) {
            val previousKeys = if (reason == UpdateReason.TAP_PREVIOUS && config.mode != WidgetMode.SEQUENTIAL) {
                repository.previousSelection(widgetId).map { it.key }
            } else {
                emptyList()
            }
            val stats = repository.getStats(widgetId)
            selection = selector.select(
                ModeRequest(
                    mode = config.mode,
                    candidates = candidates,
                    stats = stats,
                    cycleId = config.cycleId,
                    cooldownPercent = config.cooldownPercent,
                    intervalSeconds = config.intervalSeconds,
                    collageCount = config.collageCells,
                    lastPhotoKey = config.lastPhotoKey,
                    singlePhotoUri = config.singlePhotoUri,
                    now = now,
                    localEpochDay = today,
                    lastDailyEpochDay = config.lastDailyEpochDay,
                    direction = if (reason == UpdateReason.TAP_PREVIOUS) {
                        StepDirection.BACKWARD
                    } else {
                        StepDirection.FORWARD
                    },
                    forceShuffle = reason == UpdateReason.TAP_SHUFFLE,
                    forcedKeys = previousKeys,
                    random = if (config.mode.resetsAtMidnight) {
                        Random(config.widgetId.toLong() * 31L + today)
                    } else {
                        Random.Default
                    },
                ),
            )
            reused = selection.reusedCurrent
            photos = selection.keys.mapNotNull { key -> candidates.firstOrNull { it.key == key } }
        }

        if (photos.isEmpty()) {
            val published = publishPlaceholder(widgetId, config, size, "No photos found", "Tap to add photos")
            repository.updateNextUpdateAt(widgetId, 0L)
            scheduler.cancel(widgetId)
            return UpdateOutcome(
                widgetId = widgetId,
                reason = reason,
                placeholder = true,
                issues = resolved.issues,
                published = published,
                size = size,
            )
        }

        val plannedNext = SchedulePolicy.nextUpdateAt(now, config, zone)
        var scheduledAt: Long
        if (pickNew && selection != null && !reused && selection.reason != SelectionReason.EMPTY) {
            scheduledAt = plannedNext
            repository.recordSelection(
                widgetId = widgetId,
                photos = photos,
                mode = config.mode,
                cycleId = selection.cycleId,
                startedNewCycle = selection.startedNewCycle,
                shownAt = now,
                nextUpdateAt = scheduledAt,
                dailyEpochDay = if (config.mode.resetsAtMidnight) today else null,
                pruneKeys = if (resolved.complete) resolved.photos.map { it.key } else null,
            )
        } else {
            scheduledAt = if (config.nextUpdateAt > now) config.nextUpdateAt else plannedNext
            if (scheduledAt != config.nextUpdateAt) {
                repository.updateNextUpdateAt(widgetId, scheduledAt)
            }
        }

        val description = contentDescription(config, photos, resolved.issues)
        var bitmap = renderer.render(config, photos, size.widthPx, size.heightPx, now)
        var published = publisher.publish(
            widgetId,
            viewFactory.create(widgetId, bitmap, config, description, animate = pickNew),
        )
        if (!published) {
            val smaller = size.scaled(0.6f)
            bitmap = renderer.render(config, photos, smaller.widthPx, smaller.heightPx, now)
            published = publisher.publish(
                widgetId,
                viewFactory.create(widgetId, bitmap, config, description, animate = false),
            )
        }
        WidgetPreviewCache.save(context, widgetId, bitmap)

        if (scheduledAt > 0L) scheduler.schedule(widgetId, scheduledAt) else scheduler.cancel(widgetId)

        return UpdateOutcome(
            widgetId = widgetId,
            reason = reason,
            pickedKeys = photos.map { it.key },
            reusedCurrent = reused,
            issues = resolved.issues,
            nextUpdateAt = scheduledAt,
            published = published,
            size = size,
        )
    }

    private fun candidatesFor(config: WidgetConfig, resolved: ResolvedPhotos): List<PhotoRef> {
        if (config.mode == WidgetMode.SINGLE) {
            val uri = config.singlePhotoUri ?: return emptyList()
            val match = resolved.photos.firstOrNull { it.key == uri || it.uri == uri }
            return listOf(match ?: PhotoRef(uri = uri, key = uri, displayName = ""))
        }
        return resolved.photos
    }

    private suspend fun currentPhotos(
        widgetId: Int,
        config: WidgetConfig,
        candidates: List<PhotoRef>,
    ): List<PhotoRef> {
        val latestKeys = repository.latestSelection(widgetId).map { it.key }
            .ifEmpty { listOfNotNull(config.lastPhotoKey) }
        return latestKeys.mapNotNull { key -> candidates.firstOrNull { it.key == key } }
    }

    private suspend fun publishPlaceholder(
        widgetId: Int,
        config: WidgetConfig,
        size: WidgetSize,
        title: String,
        subtitle: String,
    ): Boolean {
        var bitmap: Bitmap = renderer.renderPlaceholder(
            appearance = config.appearance,
            widthPx = size.widthPx,
            heightPx = size.heightPx,
            title = title,
            subtitle = subtitle,
        )
        var published = publisher.publish(
            widgetId,
            viewFactory.create(
                widgetId = widgetId,
                bitmap = bitmap,
                config = config,
                contentDescription = "$title. $subtitle",
                tapActionOverride = TapAction.OPEN_SETTINGS,
                animate = false,
            ),
        )
        if (!published) {
            val smaller = size.scaled(0.6f)
            bitmap = renderer.renderPlaceholder(
                appearance = config.appearance,
                widthPx = smaller.widthPx,
                heightPx = smaller.heightPx,
                title = title,
                subtitle = subtitle,
            )
            published = publisher.publish(
                widgetId,
                viewFactory.create(
                    widgetId = widgetId,
                    bitmap = bitmap,
                    config = config,
                    contentDescription = "$title. $subtitle",
                    tapActionOverride = TapAction.OPEN_SETTINGS,
                    animate = false,
                ),
            )
        }
        WidgetPreviewCache.save(context, widgetId, bitmap)
        return published
    }

    private fun contentDescription(
        config: WidgetConfig,
        photos: List<PhotoRef>,
        issues: List<SourceIssue>,
    ): String {
        val names = photos.joinToString(", ") { it.displayName.ifBlank { "Photo" } }
        val base = if (photos.size == 1) {
            "${config.name}: $names"
        } else {
            "${config.name}: collage of ${photos.size} photos"
        }
        return if (issues.isEmpty()) base else "$base. Some sources are unavailable."
    }
}
