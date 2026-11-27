package dev.photowidget.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * A one-tap visual preset for a widget.
 *
 * Presets only touch the frame: corners, spacing, border, shadow and background.
 * The photo placement ([WidgetAppearance.imageFit]), quality and text overlay stay
 * exactly as the user set them, so applying a style never undoes another choice.
 */
@Serializable
enum class WidgetStyle {
    /** Just the photo on the wallpaper: no card, no shadow, nothing behind it. */
    PLAIN,

    /** Rounded card with a soft shadow: the classic photo widget. */
    NATURAL,

    /** No corners, no margin: the photo runs edge to edge. */
    FULL_BLEED,

    /** Large radius, hairline border and a floating shadow. */
    SOFT_CARD,

    /** Thick paper frame around the photo, like a printed instant photo. */
    POLAROID,

    /** Translucent blurred photo behind a thin glass border. */
    GLASS,

    /** Dark card with a bright neon outline. */
    NEON,
    ;

    fun applyTo(appearance: WidgetAppearance): WidgetAppearance = frame().applyTo(appearance)

    fun matches(appearance: WidgetAppearance): Boolean = frame().matches(appearance)

    private fun frame(): StyleFrame = when (this) {
        PLAIN -> StyleFrame(
            showCard = false,
            cornerRadiusDp = 20f,
            marginDp = 0f,
            paddingDp = 0f,
            borderWidthDp = 0f,
            borderColor = 0x40FFFFFF,
            shadowEnabled = false,
            shadowStrength = 0.3f,
            shadowSizeDp = 8f,
            backgroundStyle = BackgroundStyle.TRANSPARENT,
            backgroundColor = 0xFF10140F,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )

        NATURAL -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 24f,
            marginDp = 8f,
            paddingDp = 0f,
            borderWidthDp = 0f,
            borderColor = 0x40FFFFFF,
            shadowEnabled = true,
            shadowStrength = 0.30f,
            shadowSizeDp = 8f,
            backgroundStyle = BackgroundStyle.SOLID,
            backgroundColor = 0xFF10140F,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )

        FULL_BLEED -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 0f,
            marginDp = 0f,
            paddingDp = 0f,
            borderWidthDp = 0f,
            borderColor = 0x40FFFFFF,
            shadowEnabled = false,
            shadowStrength = 0.3f,
            shadowSizeDp = 8f,
            backgroundStyle = BackgroundStyle.SOLID,
            backgroundColor = 0xFF000000,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )

        SOFT_CARD -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 32f,
            marginDp = 10f,
            paddingDp = 0f,
            borderWidthDp = 1f,
            borderColor = 0x33FFFFFF,
            shadowEnabled = true,
            shadowStrength = 0.45f,
            shadowSizeDp = 10f,
            backgroundStyle = BackgroundStyle.SOLID,
            backgroundColor = 0xFF10140F,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )

        POLAROID -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 4f,
            marginDp = 10f,
            paddingDp = 12f,
            borderWidthDp = 0f,
            borderColor = 0x40FFFFFF,
            shadowEnabled = true,
            shadowStrength = 0.35f,
            shadowSizeDp = 8f,
            backgroundStyle = BackgroundStyle.SOLID,
            backgroundColor = 0xFFF7F2E7,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )

        GLASS -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 26f,
            marginDp = 6f,
            paddingDp = 0f,
            borderWidthDp = 1f,
            borderColor = 0x40FFFFFF,
            shadowEnabled = true,
            shadowStrength = 0.55f,
            shadowSizeDp = 12f,
            backgroundStyle = BackgroundStyle.BLUR,
            backgroundColor = 0xFF10140F,
            backgroundBlur = 0.85f,
            backgroundDim = 0.30f,
        )

        NEON -> StyleFrame(
            showCard = true,
            cornerRadiusDp = 22f,
            marginDp = 5f,
            paddingDp = 0f,
            borderWidthDp = 2f,
            borderColor = 0xFF5FE3C0,
            shadowEnabled = true,
            shadowStrength = 0.70f,
            shadowSizeDp = 12f,
            backgroundStyle = BackgroundStyle.SOLID,
            backgroundColor = 0xFF071C16,
            backgroundBlur = 0.75f,
            backgroundDim = 0.15f,
        )
    }

    companion object {
        /** The preset an appearance currently matches, or null for hand-tuned values. */
        fun of(appearance: WidgetAppearance): WidgetStyle? =
            entries.firstOrNull { it.matches(appearance.sanitized()) }
    }
}

/** The frame values a [WidgetStyle] writes. Kept private to the model module. */
private data class StyleFrame(
    val showCard: Boolean,
    val cornerRadiusDp: Float,
    val marginDp: Float,
    val paddingDp: Float,
    val borderWidthDp: Float,
    val borderColor: Long,
    val shadowEnabled: Boolean,
    val shadowStrength: Float,
    val shadowSizeDp: Float,
    val backgroundStyle: BackgroundStyle,
    val backgroundColor: Long,
    val backgroundBlur: Float,
    val backgroundDim: Float,
) {
    fun applyTo(appearance: WidgetAppearance): WidgetAppearance = appearance.copy(
        showCard = showCard,
        cornerRadiusDp = cornerRadiusDp,
        marginDp = marginDp,
        paddingDp = paddingDp,
        borderWidthDp = borderWidthDp,
        borderColor = borderColor,
        shadowEnabled = shadowEnabled,
        shadowStrength = shadowStrength,
        shadowSizeDp = shadowSizeDp,
        backgroundStyle = backgroundStyle,
        backgroundColor = backgroundColor,
        backgroundBlur = backgroundBlur,
        backgroundDim = backgroundDim,
    )

    fun matches(appearance: WidgetAppearance): Boolean =
        appearance.showCard == showCard &&
            close(appearance.cornerRadiusDp, cornerRadiusDp) &&
            close(appearance.marginDp, marginDp) &&
            close(appearance.paddingDp, paddingDp) &&
            close(appearance.borderWidthDp, borderWidthDp) &&
            appearance.borderColor == borderColor &&
            appearance.shadowEnabled == shadowEnabled &&
            close(appearance.shadowStrength, shadowStrength) &&
            close(appearance.shadowSizeDp, shadowSizeDp) &&
            appearance.backgroundStyle == backgroundStyle &&
            appearance.backgroundColor == backgroundColor &&
            close(appearance.backgroundBlur, backgroundBlur) &&
            close(appearance.backgroundDim, backgroundDim)

    private fun close(a: Float, b: Float): Boolean = abs(a - b) < 0.01f
}
