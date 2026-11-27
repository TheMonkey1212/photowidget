package dev.photowidget.core.db

import androidx.room.TypeConverter
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetMode
import kotlinx.serialization.json.Json

/**
 * Enums are stored by name and appearance is stored as JSON.
 *
 * Unknown values fall back to defaults instead of throwing, so a widget created
 * on a newer version can never crash an older install or a partially written row.
 */
class Converters {

    @TypeConverter
    fun appearanceToString(value: WidgetAppearance): String =
        json.encodeToString(WidgetAppearance.serializer(), value)

    @TypeConverter
    fun stringToAppearance(value: String?): WidgetAppearance {
        if (value.isNullOrBlank()) return WidgetAppearance()
        val decoded = runCatching { json.decodeFromString(WidgetAppearance.serializer(), value) }
            .getOrDefault(WidgetAppearance())
        return decoded.sanitized()
    }

    @TypeConverter
    fun modeToString(value: WidgetMode): String = value.name

    @TypeConverter
    fun stringToMode(value: String?): WidgetMode =
        WidgetMode.entries.firstOrNull { it.name == value } ?: WidgetMode.SMART_SHUFFLE

    @TypeConverter
    fun tapActionToString(value: TapAction): String = value.name

    @TypeConverter
    fun stringToTapAction(value: String?): TapAction =
        TapAction.entries.firstOrNull { it.name == value } ?: TapAction.NEXT

    @TypeConverter
    fun sourceTypeToString(value: PhotoSourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String?): PhotoSourceType =
        PhotoSourceType.entries.firstOrNull { it.name == value } ?: PhotoSourceType.PHOTO

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
