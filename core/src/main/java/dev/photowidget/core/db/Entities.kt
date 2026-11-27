package dev.photowidget.core.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetMode

@Entity(tableName = "widgets")
data class WidgetEntity(
    @PrimaryKey val widgetId: Int,
    val name: String,
    val mode: WidgetMode,
    val intervalSeconds: Long,
    val appearance: WidgetAppearance,
    val tapAction: TapAction,
    val transitions: Boolean,
    val cooldownPercent: Int,
    val collageCells: Int,
    val cycleId: Long,
    val sequence: Long,
    val lastPhotoKey: String?,
    val lastDailyEpochDay: Long,
    val singlePhotoUri: String?,
    val nextUpdateAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sources",
    foreignKeys = [
        ForeignKey(
            entity = WidgetEntity::class,
            parentColumns = ["widgetId"],
            childColumns = ["widgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("widgetId")],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val widgetId: Int,
    val type: PhotoSourceType,
    val locator: String,
    val label: String,
    val addedAt: Long,
)

@Entity(
    tableName = "photo_state",
    primaryKeys = ["widgetId", "photoKey"],
    foreignKeys = [
        ForeignKey(
            entity = WidgetEntity::class,
            parentColumns = ["widgetId"],
            childColumns = ["widgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("widgetId")],
)
data class PhotoStateEntity(
    val widgetId: Int,
    val photoKey: String,
    val uri: String,
    val lastShownAt: Long,
    val shownCount: Int,
    val lastPosition: Long,
    val shownInCycle: Boolean,
    val firstSeenAt: Long,
)

@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = WidgetEntity::class,
            parentColumns = ["widgetId"],
            childColumns = ["widgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("widgetId"), Index(value = ["widgetId", "shownAt"])],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val widgetId: Int,
    val photoKey: String,
    val uri: String,
    val displayName: String,
    val shownAt: Long,
    val mode: WidgetMode,
    val collage: Boolean,
)

/** A widget row joined with its photo sources, in insertion order. */
data class WidgetWithSources(
    @Embedded val widget: WidgetEntity,
    @Relation(parentColumn = "widgetId", entityColumn = "widgetId")
    val sources: List<SourceEntity>,
)
