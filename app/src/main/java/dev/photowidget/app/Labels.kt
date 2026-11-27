package dev.photowidget.app

import android.content.Context
import dev.photowidget.model.BackgroundStyle
import dev.photowidget.model.ImageFit
import dev.photowidget.model.OverlayPosition
import dev.photowidget.model.RenderQuality
import dev.photowidget.model.TapAction
import dev.photowidget.model.WidgetMode
import dev.photowidget.model.WidgetStyle

/** Central mapping from model enums to user-facing labels. */
object Labels {

    fun mode(context: Context, mode: WidgetMode): String = context.getString(
        when (mode) {
            WidgetMode.SMART_SHUFFLE -> R.string.mode_smart_shuffle
            WidgetMode.SEQUENTIAL -> R.string.mode_sequential
            WidgetMode.RANDOM -> R.string.mode_random
            WidgetMode.DAILY -> R.string.mode_daily
            WidgetMode.WEEKLY -> R.string.mode_weekly
            WidgetMode.LEAST_SHOWN -> R.string.mode_least_shown
            WidgetMode.COLLAGE -> R.string.mode_collage
            WidgetMode.SINGLE -> R.string.mode_single
        },
    )

    fun modeDescription(context: Context, mode: WidgetMode): String = context.getString(
        when (mode) {
            WidgetMode.SMART_SHUFFLE -> R.string.mode_smart_shuffle_desc
            WidgetMode.SEQUENTIAL -> R.string.mode_sequential_desc
            WidgetMode.RANDOM -> R.string.mode_random_desc
            WidgetMode.DAILY -> R.string.mode_daily_desc
            WidgetMode.WEEKLY -> R.string.mode_weekly_desc
            WidgetMode.LEAST_SHOWN -> R.string.mode_least_shown_desc
            WidgetMode.COLLAGE -> R.string.mode_collage_desc
            WidgetMode.SINGLE -> R.string.mode_single_desc
        },
    )

    fun tapAction(context: Context, action: TapAction): String = context.getString(
        when (action) {
            TapAction.NEXT -> R.string.tap_next
            TapAction.PREVIOUS -> R.string.tap_previous
            TapAction.SHUFFLE -> R.string.tap_shuffle
            TapAction.FULLSCREEN -> R.string.tap_fullscreen
            TapAction.OPEN_SETTINGS -> R.string.tap_settings
        },
    )

    fun fit(context: Context, fit: ImageFit): String = context.getString(
        when (fit) {
            ImageFit.FILL -> R.string.fit_fill
            ImageFit.FIT -> R.string.fit_fit
            ImageFit.BLUR_FILL -> R.string.fit_blur_fill
        },
    )

    fun style(context: Context, style: WidgetStyle): String = context.getString(
        when (style) {
            WidgetStyle.PLAIN -> R.string.style_plain
            WidgetStyle.NATURAL -> R.string.style_natural
            WidgetStyle.FULL_BLEED -> R.string.style_full_bleed
            WidgetStyle.SOFT_CARD -> R.string.style_soft_card
            WidgetStyle.POLAROID -> R.string.style_polaroid
            WidgetStyle.GLASS -> R.string.style_glass
            WidgetStyle.NEON -> R.string.style_neon
        },
    )

    fun background(context: Context, style: BackgroundStyle): String = context.getString(
        when (style) {
            BackgroundStyle.SOLID -> R.string.background_solid
            BackgroundStyle.BLUR -> R.string.background_blur
            BackgroundStyle.TRANSPARENT -> R.string.background_transparent
        },
    )

    fun quality(context: Context, quality: RenderQuality): String = context.getString(
        when (quality) {
            RenderQuality.HIGH -> R.string.quality_high
            RenderQuality.NORMAL -> R.string.quality_normal
            RenderQuality.LOW -> R.string.quality_low
        },
    )

    fun overlayPosition(context: Context, position: OverlayPosition): String = context.getString(
        when (position) {
            OverlayPosition.TOP_START -> R.string.overlay_position_top_start
            OverlayPosition.TOP_CENTER -> R.string.overlay_position_top_center
            OverlayPosition.TOP_END -> R.string.overlay_position_top_end
            OverlayPosition.BOTTOM_START -> R.string.overlay_position_bottom_start
            OverlayPosition.BOTTOM_CENTER -> R.string.overlay_position_bottom_center
            OverlayPosition.BOTTOM_END -> R.string.overlay_position_bottom_end
        },
    )
}
