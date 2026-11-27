package dev.photowidget.model

import kotlinx.serialization.Serializable

/**
 * Complete configuration and persisted rotation state of one widget.
 *
 * Instances are independent: two widgets never share sources, rotation state,
 * history or interval.
 */
@Serializable
data class WidgetConfig(
    val widgetId: Int,
    val name: String = "Photo widget",
    val mode: WidgetMode = WidgetMode.SMART_SHUFFLE,
    val intervalSeconds: Long = WidgetLimits.DEFAULT_INTERVAL_SECONDS,
    val appearance: WidgetAppearance = WidgetAppearance(),
    val tapAction: TapAction = TapAction.NEXT,
    /** Crossfade between photos on launchers that honour RemoteViews animations. */
    val transitions: Boolean = false,
    val cooldownPercent: Int = WidgetLimits.DEFAULT_COOLDOWN_PERCENT,
    val collageCells: Int = 4,
    val cycleId: Long = 1L,
    /** Monotonic counter used to order recently shown photos. */
    val sequence: Long = 0L,
    val lastPhotoKey: String? = null,
    /** Epoch day (local) of the last pick in DAILY mode. */
    val lastDailyEpochDay: Long = 0L,
    val singlePhotoUri: String? = null,
    val nextUpdateAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sources: List<PhotoSource> = emptyList(),
) {
    val hasSources: Boolean get() = sources.isNotEmpty()

    /** True when the widget needs no rotation timer (manual content only). */
    val isManual: Boolean get() = mode == WidgetMode.SINGLE

    fun sanitized(): WidgetConfig = copy(
        name = name.trim().take(60).ifBlank { "Photo widget" },
        intervalSeconds = WidgetLimits.clampInterval(intervalSeconds),
        cooldownPercent = WidgetLimits.clampCooldown(cooldownPercent),
        collageCells = collageCells.coerceIn(WidgetLimits.MIN_COLLAGE_CELLS, WidgetLimits.MAX_COLLAGE_CELLS),
        appearance = appearance.sanitized(),
    )
}

/** One entry in a widget's "recently shown" history. */
data class WidgetHistoryItem(
    val id: Long,
    val widgetId: Int,
    val photoKey: String,
    val uri: String,
    val displayName: String = "",
    val shownAt: Long = 0L,
    val mode: WidgetMode = WidgetMode.SMART_SHUFFLE,
    /** True for one of several collage photos. */
    val collage: Boolean = false,
)

/** Combined data the UI and the widget updater need. */
data class WidgetSnapshot(
    val config: WidgetConfig,
    val sources: List<PhotoSource> = emptyList(),
)
