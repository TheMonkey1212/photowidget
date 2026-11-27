package dev.photowidget.core.data

import android.content.Context
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetLimits
import dev.photowidget.model.WidgetMode
import kotlinx.serialization.json.Json

/**
 * Small, local-only preferences: defaults for new widgets, one-off UI hints and
 * the launcher-side flipper child index used for crossfades.
 *
 * No photo data, history or personal information is stored here.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("photo_widget_prefs", Context.MODE_PRIVATE)

    var defaultMode: WidgetMode
        get() = enumValue(key = KEY_DEFAULT_MODE, fallback = WidgetMode.SMART_SHUFFLE)
        set(value) = prefs.edit().putString(KEY_DEFAULT_MODE, value.name).apply()

    var defaultIntervalSeconds: Long
        get() = prefs.getLong(KEY_DEFAULT_INTERVAL, WidgetLimits.DEFAULT_INTERVAL_SECONDS)
            .let(WidgetLimits::clampInterval)
        set(value) = prefs.edit().putLong(KEY_DEFAULT_INTERVAL, WidgetLimits.clampInterval(value)).apply()

    var defaultCooldownPercent: Int
        get() = prefs.getInt(KEY_DEFAULT_COOLDOWN, WidgetLimits.DEFAULT_COOLDOWN_PERCENT)
            .let(WidgetLimits::clampCooldown)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_COOLDOWN, WidgetLimits.clampCooldown(value)).apply()

    var defaultTapAction: TapAction
        get() = enumValue(key = KEY_DEFAULT_TAP, fallback = TapAction.NEXT)
        set(value) = prefs.edit().putString(KEY_DEFAULT_TAP, value.name).apply()

    var defaultTransitions: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_TRANSITIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_TRANSITIONS, value).apply()

    /** Appearance used when a new widget is created. */
    var defaultAppearance: WidgetAppearance
        get() {
            val raw = prefs.getString(KEY_DEFAULT_APPEARANCE, null) ?: return WidgetAppearance()
            val decoded = runCatching {
                json.decodeFromString(WidgetAppearance.serializer(), raw)
            }.getOrDefault(WidgetAppearance())
            return decoded.sanitized()
        }
        set(value) = prefs.edit()
            .putString(KEY_DEFAULT_APPEARANCE, json.encodeToString(WidgetAppearance.serializer(), value.sanitized()))
            .apply()

    var exactAlarmHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_EXACT_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_EXACT_HINT, value).apply()

    var batteryHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_HINT, value).apply()

    var lastWidgetId: Int
        get() = prefs.getInt(KEY_LAST_WIDGET, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_WIDGET, value).apply()

    /** Source widget for a copy that is waiting to be pinned by the launcher. */
    var pendingDuplicateSource: Int
        get() = prefs.getInt(KEY_PENDING_DUPLICATE, -1)
        set(value) = prefs.edit().putInt(KEY_PENDING_DUPLICATE, value).apply()

    var pendingDuplicateAt: Long
        get() = prefs.getLong(KEY_PENDING_DUPLICATE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_DUPLICATE_AT, value).apply()

    fun clearPendingDuplicate() {
        prefs.edit()
            .remove(KEY_PENDING_DUPLICATE)
            .remove(KEY_PENDING_DUPLICATE_AT)
            .apply()
    }

    /**
     * Widgets the user deleted in the app while a launcher may still host them.
     *
     * The app cannot unbind a launcher widget, so these ids must not be recreated by
     * a late APPWIDGET_UPDATE; they only show a "removed" placeholder until the user
     * removes the widget from the home screen.
     */
    var deletedWidgetIds: Set<Int>
        get() = prefs.getStringSet(KEY_DELETED_WIDGETS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        set(value) = prefs.edit()
            .putStringSet(KEY_DELETED_WIDGETS, value.map(Int::toString).toSet())
            .apply()

    fun markWidgetDeleted(widgetId: Int) {
        deletedWidgetIds = deletedWidgetIds + widgetId
    }

    fun clearDeletedWidget(widgetId: Int) {
        deletedWidgetIds = deletedWidgetIds - widgetId
    }

    fun lastFlipperChild(widgetId: Int): Int = prefs.getInt("$KEY_FLIPPER_PREFIX$widgetId", 0)

    fun setLastFlipperChild(widgetId: Int, child: Int) {
        prefs.edit().putInt("$KEY_FLIPPER_PREFIX$widgetId", child).apply()
    }

    /** Prefilled configuration for a brand new widget. */
    fun newWidgetConfig(widgetId: Int, now: Long): WidgetConfig = WidgetConfig(
        widgetId = widgetId,
        mode = defaultMode,
        intervalSeconds = defaultIntervalSeconds,
        appearance = defaultAppearance,
        tapAction = defaultTapAction,
        cooldownPercent = defaultCooldownPercent,
        transitions = defaultTransitions,
        createdAt = now,
        updatedAt = now,
    )

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    private companion object {
        const val KEY_DEFAULT_MODE = "default_mode"
        const val KEY_DEFAULT_INTERVAL = "default_interval"
        const val KEY_DEFAULT_COOLDOWN = "default_cooldown"
        const val KEY_DEFAULT_TAP = "default_tap"
        const val KEY_DEFAULT_TRANSITIONS = "default_transitions"
        const val KEY_DEFAULT_APPEARANCE = "default_appearance"
        const val KEY_EXACT_HINT = "exact_alarm_hint"
        const val KEY_BATTERY_HINT = "battery_hint"
        const val KEY_LAST_WIDGET = "last_widget"
        const val KEY_PENDING_DUPLICATE = "pending_duplicate"
        const val KEY_PENDING_DUPLICATE_AT = "pending_duplicate_at"
        const val KEY_FLIPPER_PREFIX = "flipper_"
        const val KEY_DELETED_WIDGETS = "deleted_widgets"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}
