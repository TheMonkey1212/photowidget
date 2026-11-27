package dev.photowidget.model

import kotlinx.serialization.Serializable

/**
 * A single photo the app can display.
 *
 * [key] is a stable identity used for history and shuffle bookkeeping:
 * - MediaStore / Photo Picker: the content URI
 * - SAF documents: the document URI
 * If the same file is reachable through two different URIs it is de-duplicated
 * by URI, which is the only identity Android exposes without extra permissions.
 */
@Serializable
data class PhotoRef(
    val uri: String,
    val key: String = uri,
    val displayName: String = "",
    /** Epoch millis from MediaStore DATE_TAKEN, or last-modified for SAF files. */
    val dateTaken: Long = 0L,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Human readable origin, e.g. album name. */
    val sourceLabel: String = "",
    /** MediaStore bucket id when known, useful for debugging and info overlays. */
    val bucketId: String? = null,
    /**
     * Extra identities for cross-source de-duplication, e.g. the same file seen
     * through MediaStore (`media:<id>`, `path:Pictures/x.jpg`) and through a SAF
     * tree (`path:Pictures/x.jpg`). Empty means "identity is [key]".
     */
    val dedupeKeys: Set<String> = emptySet(),
) {
    val isImage: Boolean
        get() = mimeType == null || mimeType.startsWith("image/")
}

/** A photo source attached to one widget. */
@Serializable
data class PhotoSource(
    val id: Long = 0L,
    val widgetId: Int = 0,
    val type: PhotoSourceType,
    /**
     * Meaning depends on [type]:
     * - PHOTO: content URI
     * - ALBUM: MediaStore bucket id (numeric string)
     * - FOLDER: SAF tree URI
     */
    val locator: String,
    val label: String = "",
    val addedAt: Long = 0L,
)

/** A MediaStore album shown in the library screen. */
data class AlbumInfo(
    val bucketId: String,
    val name: String,
    val photoCount: Int,
    val coverUri: String? = null,
)

/** Per-widget, per-photo shuffle bookkeeping. */
data class PhotoStat(
    val key: String,
    /** 0 means "never shown". */
    val lastShownAt: Long = 0L,
    val shownCount: Int = 0,
    /** Monotonic display order inside the widget, used for cooldown windows. */
    val lastPosition: Long = 0L,
    /** True once this photo has been shown in the widget's current cycle. */
    val shownInCycle: Boolean = false,
    val firstSeenAt: Long = 0L,
)

/** A source that could not be read (revoked permission, removed folder, ...). */
data class SourceIssue(
    val sourceId: Long,
    val sourceLabel: String,
    val message: String,
    val permissionDenied: Boolean = false,
)

/**
 * Result of resolving all sources of a widget.
 *
 * [complete] is false when at least one source failed, in which case callers must
 * not prune stored history (the photos may come back when access is restored).
 */
data class ResolvedPhotos(
    val photos: List<PhotoRef> = emptyList(),
    val issues: List<SourceIssue> = emptyList(),
) {
    val complete: Boolean get() = issues.isEmpty()
    val isEmpty: Boolean get() = photos.isEmpty()
}
