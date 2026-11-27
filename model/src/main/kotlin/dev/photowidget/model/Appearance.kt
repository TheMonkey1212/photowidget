package dev.photowidget.model

import kotlinx.serialization.Serializable

/**
 * Everything visual about a widget. Stored as JSON in the local database so new
 * fields can be added without a schema migration.
 *
 * Every numeric field is sanitised before use ([WidgetAppearance.sanitized]) so a
 * corrupted or hand-edited value can never crash rendering.
 */
@Serializable
data class WidgetAppearance(
    /** Outer corner radius in dp. */
    val cornerRadiusDp: Float = 20f,
    /** Space between the bitmap edge and the card, in dp. */
    val marginDp: Float = 0f,
    /** Space between the card edge and the photo, in dp. */
    val paddingDp: Float = 0f,
    /**
     * Draws a backing card behind the photo: margin, shadow, background and border.
     *
     * Off by default, so a widget is just the image on the wallpaper. Turning it on
     * brings the frame, shadow and background controls back into play.
     */
    val showCard: Boolean = false,
    /** Border stroke width in dp (0 disables the border). */
    val borderWidthDp: Float = 0f,
    /** Border colour, ARGB. */
    val borderColor: Long = 0x40FFFFFF,
    val shadowEnabled: Boolean = false,
    /** 0..1; drives shadow alpha. */
    val shadowStrength: Float = 0.30f,
    val shadowSizeDp: Float = 8f,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.TRANSPARENT,
    /** ARGB colour used for [BackgroundStyle.SOLID] and as a fallback. */
    val backgroundColor: Long = 0xFF10140F,
    /** 0..1 blur strength for [BackgroundStyle.BLUR]. */
    val backgroundBlur: Float = 0.75f,
    /** 0..1 darkening applied to the blurred background. */
    val backgroundDim: Float = 0.15f,
    val imageFit: ImageFit = ImageFit.FILL,
    /** Focal point, 0..1 across the photo. Used for cropping. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val renderQuality: RenderQuality = RenderQuality.NORMAL,
    /** Gap between collage cells in dp. The grid itself follows the photo count. */
    val collageGapDp: Float = 3f,
    val overlay: OverlayAppearance = OverlayAppearance(),
) {
    fun sanitized(): WidgetAppearance = copy(
        cornerRadiusDp = cornerRadiusDp.coerceIn(0f, 200f),
        marginDp = marginDp.coerceIn(0f, 48f),
        paddingDp = paddingDp.coerceIn(0f, 48f),
        borderWidthDp = borderWidthDp.coerceIn(0f, 24f),
        shadowStrength = shadowStrength.coerceIn(0f, 1f),
        shadowSizeDp = shadowSizeDp.coerceIn(0f, 32f),
        backgroundBlur = backgroundBlur.coerceIn(0f, 1f),
        backgroundDim = backgroundDim.coerceIn(0f, 0.85f),
        focusX = focusX.coerceIn(0f, 1f),
        focusY = focusY.coerceIn(0f, 1f),
        collageGapDp = collageGapDp.coerceIn(0f, 24f),
        overlay = overlay.sanitized(),
    )
}

@Serializable
data class OverlayAppearance(
    val showDate: Boolean = false,
    val showTime: Boolean = false,
    val showPhotoName: Boolean = false,
    val showPhotoDate: Boolean = false,
    val position: OverlayPosition = OverlayPosition.BOTTOM_START,
    val textSizeSp: Float = 12f,
    /** ARGB text colour. */
    val textColor: Long = 0xFFFFFFFF,
    /** Draws a translucent plate behind the text for readability. */
    val scrim: Boolean = true,
    /** 0..1 scrim opacity. */
    val scrimStrength: Float = 0.45f,
    /** SimpleDateFormat pattern, e.g. "EEE, MMM d". */
    val datePattern: String = "EEE, MMM d",
    /** SimpleDateFormat pattern, e.g. "HH:mm". */
    val timePattern: String = "HH:mm",
    /** Optional fixed caption placed above the generated lines. */
    val customText: String = "",
) {
    val enabled: Boolean
        get() = showDate || showTime || showPhotoName || showPhotoDate || customText.isNotBlank()

    fun sanitized(): OverlayAppearance = copy(
        textSizeSp = textSizeSp.coerceIn(8f, 28f),
        scrimStrength = scrimStrength.coerceIn(0f, 0.9f),
        datePattern = datePattern.take(40).ifBlank { "EEE, MMM d" },
        timePattern = timePattern.take(20).ifBlank { "HH:mm" },
        customText = customText.take(60),
    )
}

/** Numeric limits shared by UI and scheduler. */
object WidgetLimits {
    /** 10 seconds keeps refresh work and battery use reasonable. */
    const val MIN_INTERVAL_SECONDS: Long = 10

    /** One week per refresh is the slowest useful setting. */
    const val MAX_INTERVAL_SECONDS: Long = 7L * 24 * 60 * 60

    const val DEFAULT_INTERVAL_SECONDS: Long = 60 * 60

    const val MIN_COOLDOWN_PERCENT: Int = 0
    const val MAX_COOLDOWN_PERCENT: Int = 100
    const val DEFAULT_COOLDOWN_PERCENT: Int = 20

    const val MIN_COLLAGE_CELLS: Int = 2
    const val MAX_COLLAGE_CELLS: Int = 9

    fun clampInterval(seconds: Long): Long = seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

    fun clampCooldown(percent: Int): Int = percent.coerceIn(MIN_COOLDOWN_PERCENT, MAX_COOLDOWN_PERCENT)
}
