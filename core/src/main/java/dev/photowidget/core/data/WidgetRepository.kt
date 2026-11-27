package dev.photowidget.core.data

import androidx.room.withTransaction
import dev.photowidget.core.db.HistoryEntity
import dev.photowidget.core.db.PhotoStateEntity
import dev.photowidget.core.db.PhotoWidgetDatabase
import dev.photowidget.core.db.SourceEntity
import dev.photowidget.core.db.toModel
import dev.photowidget.core.db.toStat
import dev.photowidget.core.db.toEntity
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.PhotoStat
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetHistoryItem
import dev.photowidget.model.WidgetMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for widget configuration, photo sources and rotation
 * history. Every mutation that touches more than one table runs in a Room
 * transaction so a crash can never leave a widget half-updated.
 */
class WidgetRepository(
    private val db: PhotoWidgetDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val widgets = db.widgetDao()
    private val sources = db.sourceDao()
    private val states = db.photoStateDao()
    private val history = db.historyDao()

    // ---------------------------------------------------------------- reads

    fun observeWidgets(): Flow<List<WidgetConfig>> =
        widgets.observeAll().map { list -> list.map { it.toModel() } }

    fun observeWidget(widgetId: Int): Flow<WidgetConfig?> =
        widgets.observe(widgetId).map { it?.toModel() }

    fun observeSourceCount(): Flow<Int> = widgets.observeCount()

    fun observeHistory(widgetId: Int, limit: Int = HISTORY_LIMIT): Flow<List<WidgetHistoryItem>> =
        history.observeRecent(widgetId, limit).map { list -> list.map { it.toModel() } }

    suspend fun getWidget(widgetId: Int): WidgetConfig? = widgets.get(widgetId)?.toModel()

    suspend fun getAllWidgets(): List<WidgetConfig> = widgets.getAll().map { it.toModel() }

    suspend fun getSources(widgetId: Int): List<PhotoSource> =
        sources.getForWidget(widgetId).map { it.toModel() }

    suspend fun getHistory(widgetId: Int, limit: Int = HISTORY_LIMIT): List<WidgetHistoryItem> =
        history.recent(widgetId, limit).map { it.toModel() }

    suspend fun getStats(widgetId: Int): Map<String, PhotoStat> =
        states.getForWidget(widgetId).associate { it.photoKey to it.toStat() }

    suspend fun previousPhoto(widgetId: Int, currentKey: String?): PhotoRef? =
        history.previousDistinct(widgetId, currentKey)?.let {
            PhotoRef(uri = it.uri, key = it.photoKey, displayName = it.displayName)
        }

    /** Photos of the most recent displayed selection (a collage group or a single photo). */
    suspend fun latestSelection(widgetId: Int): List<PhotoRef> {
        val latest = history.recent(widgetId, 1).firstOrNull() ?: return emptyList()
        return history.groupAt(widgetId, latest.shownAt).map {
            PhotoRef(uri = it.uri, key = it.photoKey, displayName = it.displayName)
        }
    }

    /** Photos of the selection shown before the current one. */
    suspend fun previousSelection(widgetId: Int): List<PhotoRef> {
        val latest = history.recent(widgetId, 1).firstOrNull() ?: return emptyList()
        val previousTime = history.previousGroupTime(widgetId, latest.shownAt) ?: return emptyList()
        return history.groupAt(widgetId, previousTime).map {
            PhotoRef(uri = it.uri, key = it.photoKey, displayName = it.displayName)
        }
    }

    /** Widgets whose scheduled refresh is overdue; used by the watchdog worker. */
    suspend fun getWidgetsDue(now: Long): List<WidgetConfig> =
        getAllWidgets().filter {
            !it.isManual && it.nextUpdateAt in 1..now && it.hasSources
        }

    // --------------------------------------------------------------- writes

    /** Creates a default configuration if the launcher added an unconfigured widget. */
    suspend fun ensureWidget(widgetId: Int, name: String? = null): WidgetConfig {
        widgets.get(widgetId)?.let { return it.toModel() }
        val now = clock()
        val config = WidgetConfig(
            widgetId = widgetId,
            name = name ?: "Photo widget",
            createdAt = now,
            updatedAt = now,
        ).sanitized()
        widgets.upsert(config.toEntity())
        return config
    }

    /**
     * Persists the editable fields and replaces the source list.
     * Rotation state (cycle, history, last photo) is intentionally preserved.
     */
    suspend fun saveConfig(config: WidgetConfig): WidgetConfig = db.withTransaction {
        val now = clock()
        val sanitized = config.sanitized()
        val existing = widgets.getEntity(config.widgetId)
        if (existing == null) {
            widgets.upsert(
                sanitized.copy(
                    createdAt = if (sanitized.createdAt > 0) sanitized.createdAt else now,
                    updatedAt = now,
                ).toEntity(),
            )
        } else {
            widgets.updateEditable(
                widgetId = sanitized.widgetId,
                name = sanitized.name,
                mode = sanitized.mode,
                intervalSeconds = sanitized.intervalSeconds,
                appearance = sanitized.appearance,
                tapAction = sanitized.tapAction,
                transitions = sanitized.transitions,
                cooldownPercent = sanitized.cooldownPercent,
                collageCells = sanitized.collageCells,
                singlePhotoUri = sanitized.singlePhotoUri,
                updatedAt = now,
            )
        }
        sources.deleteForWidget(sanitized.widgetId)
        sanitized.sources.forEachIndexed { index, source ->
            sources.insert(
                source.copy(
                    id = 0L,
                    widgetId = sanitized.widgetId,
                    addedAt = if (source.addedAt > 0L) source.addedAt else now + index,
                ).toEntity(),
            )
        }
        loadWidget(sanitized.widgetId) ?: sanitized.copy(updatedAt = now)
    }

    /** Adds one source and returns its id (or the id of the identical existing source). */
    suspend fun addSource(
        widgetId: Int,
        type: PhotoSourceType,
        locator: String,
        label: String,
    ): Long = db.withTransaction {
        ensureWidgetUnsafe(widgetId)
        val existing = sources.getForWidget(widgetId)
            .firstOrNull { it.type == type && it.locator == locator }
        if (existing != null) return@withTransaction existing.id
        sources.insert(
            SourceEntity(
                widgetId = widgetId,
                type = type,
                locator = locator,
                label = label,
                addedAt = clock(),
            ),
        )
    }

    suspend fun removeSource(widgetId: Int, sourceId: Long) =
        sources.deleteById(widgetId, sourceId)

    suspend fun setSinglePhoto(widgetId: Int, uri: String?) = db.withTransaction {
        ensureWidgetUnsafe(widgetId)
        widgets.setSinglePhoto(widgetId, uri)
    }

    suspend fun updateNextUpdateAt(widgetId: Int, at: Long) = widgets.setNextUpdateAt(widgetId, at)

    suspend fun deleteWidget(widgetId: Int) = db.withTransaction {
        widgets.delete(widgetId)
    }

    /** Copies configuration and sources into a brand new, independent widget. */
    suspend fun duplicateWidget(sourceWidgetId: Int, newWidgetId: Int): WidgetConfig? =
        db.withTransaction {
            val original = widgets.get(sourceWidgetId) ?: return@withTransaction null
            val model = original.toModel()
            val now = clock()
            val copy = model.copy(
                widgetId = newWidgetId,
                name = ("${original.widget.name} copy").take(60),
                cycleId = 1L,
                sequence = 0L,
                lastPhotoKey = null,
                lastDailyEpochDay = 0L,
                nextUpdateAt = 0L,
                createdAt = now,
                updatedAt = now,
            ).sanitized()
            widgets.upsert(copy.copy(sources = emptyList()).toEntity())
            original.sources
                .sortedWith(compareBy({ it.addedAt }, { it.id }))
                .forEachIndexed { index, source ->
                    sources.insert(
                        source.copy(
                            id = 0L,
                            widgetId = newWidgetId,
                            addedAt = now + index,
                        ),
                    )
                }
            loadWidget(newWidgetId)
        }

    /**
     * Records one displayed selection: updates per-photo stats, cycle membership,
     * history and the widget's next refresh time in a single transaction.
     */
    suspend fun recordSelection(
        widgetId: Int,
        photos: List<PhotoRef>,
        mode: WidgetMode,
        cycleId: Long,
        startedNewCycle: Boolean,
        shownAt: Long,
        nextUpdateAt: Long,
        dailyEpochDay: Long? = null,
        /** All currently resolvable photo keys; pass null when resolution was incomplete. */
        pruneKeys: List<String>? = null,
    ) = db.withTransaction {
        val widget = widgets.getEntity(widgetId) ?: return@withTransaction
        if (startedNewCycle) states.clearCycle(widgetId)

        var sequence = widget.sequence
        val existing = states.getForWidget(widgetId).associateBy { it.photoKey }
        val updated = photos.map { photo ->
            sequence += 1
            val old = existing[photo.key]
            PhotoStateEntity(
                widgetId = widgetId,
                photoKey = photo.key,
                uri = photo.uri,
                lastShownAt = shownAt,
                shownCount = (old?.shownCount ?: 0) + 1,
                lastPosition = sequence,
                shownInCycle = true,
                firstSeenAt = old?.firstSeenAt ?: shownAt,
            )
        }
        if (updated.isNotEmpty()) states.upsertAll(updated)

        if (photos.isNotEmpty()) {
            history.insertAll(
                photos.map { photo ->
                    HistoryEntity(
                        widgetId = widgetId,
                        photoKey = photo.key,
                        uri = photo.uri,
                        displayName = photo.displayName,
                        shownAt = shownAt,
                        mode = mode,
                        collage = photos.size > 1,
                    )
                },
            )
            history.trim(widgetId, HISTORY_LIMIT)
        }

        widgets.updateRotationState(
            widgetId = widgetId,
            sequence = sequence,
            cycleId = cycleId,
            lastPhotoKey = photos.firstOrNull()?.key ?: widget.lastPhotoKey,
            lastDailyEpochDay = dailyEpochDay ?: widget.lastDailyEpochDay,
            nextUpdateAt = nextUpdateAt,
            updatedAt = shownAt,
        )

        if (pruneKeys != null && pruneKeys.isNotEmpty()) {
            pruneKeys.chunked(PRUNE_CHUNK).forEach { chunk -> states.prune(widgetId, chunk) }
        }
    }

    /** Forgets rotation state and history. The current image stays on screen. */
    suspend fun resetHistory(widgetId: Int) = db.withTransaction {
        states.clear(widgetId)
        history.clear(widgetId)
        widgets.resetRotation(widgetId)
    }

    suspend fun resetAllHistories() = db.withTransaction {
        states.clearAll()
        history.clearAll()
        widgets.resetAllRotation()
    }

    suspend fun historyCount(widgetId: Int): Int = history.count(widgetId)

    // -------------------------------------------------------------- helpers

    private suspend fun loadWidget(widgetId: Int): WidgetConfig? = widgets.get(widgetId)?.toModel()

    private suspend fun ensureWidgetUnsafe(widgetId: Int) {
        if (widgets.exists(widgetId) == 0) ensureWidget(widgetId)
    }

    companion object {
        const val HISTORY_LIMIT = 200
        private const val PRUNE_CHUNK = 400
    }
}
