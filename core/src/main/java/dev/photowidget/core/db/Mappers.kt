package dev.photowidget.core.db

import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoStat
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetHistoryItem

internal fun SourceEntity.toModel(): PhotoSource = PhotoSource(
    id = id,
    widgetId = widgetId,
    type = type,
    locator = locator,
    label = label,
    addedAt = addedAt,
)

internal fun PhotoSource.toEntity(): SourceEntity = SourceEntity(
    id = id,
    widgetId = widgetId,
    type = type,
    locator = locator,
    label = label,
    addedAt = addedAt,
)

internal fun WidgetWithSources.toModel(): WidgetConfig {
    val w = widget
    return WidgetConfig(
        widgetId = w.widgetId,
        name = w.name,
        mode = w.mode,
        intervalSeconds = w.intervalSeconds,
        appearance = w.appearance,
        tapAction = w.tapAction,
        transitions = w.transitions,
        cooldownPercent = w.cooldownPercent,
        collageCells = w.collageCells,
        cycleId = w.cycleId,
        sequence = w.sequence,
        lastPhotoKey = w.lastPhotoKey,
        lastDailyEpochDay = w.lastDailyEpochDay,
        singlePhotoUri = w.singlePhotoUri,
        nextUpdateAt = w.nextUpdateAt,
        createdAt = w.createdAt,
        updatedAt = w.updatedAt,
        sources = sources.sortedWith(compareBy({ it.addedAt }, { it.id })).map { it.toModel() },
    )
}

internal fun WidgetConfig.toEntity(): WidgetEntity = WidgetEntity(
    widgetId = widgetId,
    name = name,
    mode = mode,
    intervalSeconds = intervalSeconds,
    appearance = appearance,
    tapAction = tapAction,
    transitions = transitions,
    cooldownPercent = cooldownPercent,
    collageCells = collageCells,
    cycleId = cycleId,
    sequence = sequence,
    lastPhotoKey = lastPhotoKey,
    lastDailyEpochDay = lastDailyEpochDay,
    singlePhotoUri = singlePhotoUri,
    nextUpdateAt = nextUpdateAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun PhotoStateEntity.toStat(): PhotoStat = PhotoStat(
    key = photoKey,
    lastShownAt = lastShownAt,
    shownCount = shownCount,
    lastPosition = lastPosition,
    shownInCycle = shownInCycle,
    firstSeenAt = firstSeenAt,
)

internal fun HistoryEntity.toModel(): WidgetHistoryItem = WidgetHistoryItem(
    id = id,
    widgetId = widgetId,
    photoKey = photoKey,
    uri = uri,
    displayName = displayName,
    shownAt = shownAt,
    mode = mode,
    collage = collage,
)
