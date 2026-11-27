package dev.photowidget.app

/**
 * Edits an existing widget from the in-app widget list. The configuration screen
 * is shared with the launcher's add-widget flow; the widget id arrives in the
 * intent extra [dev.photowidget.widget.WidgetIntents.EXTRA_WIDGET_ID].
 */
class WidgetEditActivity : WidgetConfigureActivity()
