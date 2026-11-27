package dev.photowidget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a widget decides which photo(s) to show next.
 *
 * All modes work entirely offline and keep their own persistent state per widget.
 */
@Serializable
enum class WidgetMode {
    /** Weighted, fair rotation that avoids showing the same photo twice in a row. */
    SMART_SHUFFLE,

    /** Walks the resolved photo list in a stable order. */
    SEQUENTIAL,

    /** Uniform random, never repeating the same photo twice in a row (unless only one). */
    RANDOM,

    /** Picks once per calendar day (local midnight) and shows that photo until tomorrow. */
    DAILY,

    /** One photo per weekday, in a fixed order, swapped at local midnight. */
    WEEKLY,

    /** Always advances to the photo that has been shown the fewest times. */
    LEAST_SHOWN,

    /** Combines several photos into one grid. */
    COLLAGE,

    /** Always shows one manually chosen photo. */
    SINGLE,
    ;

    val isMultiPhoto: Boolean get() = this == COLLAGE

    /** Modes that consult the shuffle engine for a fair pick. */
    val usesFairRotation: Boolean
        get() = this == SMART_SHUFFLE || this == DAILY || this == COLLAGE

    /** Modes that pick once a day and therefore reset at local midnight. */
    val resetsAtMidnight: Boolean get() = this == DAILY || this == WEEKLY
}

/** What a single tap on the widget does. */
@Serializable
enum class TapAction {
    NEXT,
    PREVIOUS,
    SHUFFLE,
    FULLSCREEN,
    OPEN_SETTINGS,
}

/** How a photo is placed inside the widget frame. Stretching is never used. */
@Serializable
enum class ImageFit {
    /** Crops/scales to cover the frame completely (no bars, no stretching). */
    FILL,

    /**
     * Shows the complete photo at its own aspect ratio, scaled to fit the widget.
     *
     * Nothing is painted behind or beside it: the leftover space stays transparent,
     * so the widget shows just the image, whatever shape it is. The old name was
     * AMBIENT, kept here so widgets saved by earlier versions keep their setting.
     */
    @SerialName("AMBIENT")
    FIT,

    /** Full photo fitted on top of an enlarged, blurred copy of itself. */
    BLUR_FILL,
}

/** What sits behind the photo. */
@Serializable
enum class BackgroundStyle {
    SOLID,
    BLUR,
    TRANSPARENT,
}

/** Corner of the widget used for text overlays. */
@Serializable
enum class OverlayPosition {
    TOP_START,
    TOP_CENTER,
    TOP_END,
    BOTTOM_START,
    BOTTOM_CENTER,
    BOTTOM_END,
    ;

    val isTop: Boolean get() = this == TOP_START || this == TOP_CENTER || this == TOP_END
    val isStart: Boolean get() = this == TOP_START || this == BOTTOM_START
    val isEnd: Boolean get() = this == TOP_END || this == BOTTOM_END
}

/** Rendering quality / memory trade-off. */
@Serializable
enum class RenderQuality {
    HIGH,
    NORMAL,
    LOW,
}

/** Kind of photo source attached to a widget. */
@Serializable
enum class PhotoSourceType {
    /** A single content URI (system Photo Picker or a saved document). */
    PHOTO,

    /** A MediaStore album, addressed by bucket id. */
    ALBUM,

    /** A Storage Access Framework folder tree, addressed by its tree URI. */
    FOLDER,
}
